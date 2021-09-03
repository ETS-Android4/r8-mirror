// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.optimize.argumentpropagation;

import static com.android.tools.r8.utils.MapUtils.ignoreKey;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodSignature;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.ObjectAllocationInfoCollection;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.RewrittenPrototypeDescription.ArgumentInfoCollection;
import com.android.tools.r8.graph.RewrittenPrototypeDescription.RemovedArgumentInfo;
import com.android.tools.r8.ir.analysis.value.AbstractValue;
import com.android.tools.r8.ir.optimize.info.CallSiteOptimizationInfo;
import com.android.tools.r8.ir.optimize.info.ConcreteCallSiteOptimizationInfo;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.Pair;
import com.android.tools.r8.utils.collections.DexMethodSignatureSet;
import com.android.tools.r8.utils.collections.ProgramMethodSet;
import com.google.common.collect.Iterables;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

public class ArgumentPropagatorProgramOptimizer {

  private final AppView<AppInfoWithLiveness> appView;

  private final DexItemFactory dexItemFactory;

  private final InternalOptions options;

  private final Map<DexMethodSignature, IntSet> removableVirtualMethodParameters = new HashMap<>();

  // Reserved names, i.e., mappings from pairs (old method signature, number of removed arguments)
  // to the new method signature for that method.
  private final Map<DexMethodSignature, Map<IntSet, DexMethodSignature>> newMethodSignatures =
      new HashMap<>();

  // Occupied method signatures (inverse of reserved names). Used to effectively check if a given
  // method signature is already reserved.
  private final Map<DexMethodSignature, Pair<IntSet, DexMethodSignature>> occupiedMethodSignatures =
      new HashMap<>();

  public ArgumentPropagatorProgramOptimizer(AppView<AppInfoWithLiveness> appView) {
    this.appView = appView;
    this.dexItemFactory = appView.dexItemFactory();
    this.options = appView.options();
  }

  // TODO(b/190154391): Remove unused parameters by simulating they are constant.
  // TODO(b/190154391): Strengthen the static type of parameters.
  // TODO(b/190154391): If we learn that a method returns a constant, then consider changing its
  //  return type to void.
  // TODO(b/69963623): If we optimize a method to be unconditionally throwing (because it has a
  //  bottom parameter), then for each caller that becomes unconditionally throwing, we could
  //  also enqueue the caller's callers for reprocessing. This would propagate the throwing
  //  information to all call sites.
  public ArgumentPropagatorGraphLens.Builder optimize(
      Set<DexProgramClass> stronglyConnectedProgramClasses) {
    // First reserve pinned method signatures.
    reservePinnedMethodSignatures(stronglyConnectedProgramClasses);

    // To ensure that we preserve the overriding relationships between methods, we only remove a
    // constant or unused parameter from a virtual method when it can be removed from all other
    // virtual methods in the component with the same method signature.
    computeRemovableVirtualMethodParameters(stronglyConnectedProgramClasses);

    // Build a graph lens while visiting the classes in the component.
    // TODO(b/190154391): Consider visiting the interfaces first, and then processing the
    //  (non-interface) classes in top-down order to reduce the amount of reserved names.
    ArgumentPropagatorGraphLens.Builder partialGraphLensBuilder =
        ArgumentPropagatorGraphLens.builder(appView);
    for (DexProgramClass clazz : stronglyConnectedProgramClasses) {
      visitClass(clazz, partialGraphLensBuilder);
    }
    return partialGraphLensBuilder;
  }

  private void reservePinnedMethodSignatures(Set<DexProgramClass> stronglyConnectedProgramClasses) {
    DexMethodSignatureSet pinnedMethodSignatures = DexMethodSignatureSet.create();
    for (DexProgramClass clazz : stronglyConnectedProgramClasses) {
      clazz.forEachProgramMethod(
          method -> {
            if (!appView.getKeepInfo(method).isShrinkingAllowed(options)) {
              pinnedMethodSignatures.add(method.getMethodSignature());
            }
          });
    }
    pinnedMethodSignatures.forEach(
        signature -> reserveMethodSignature(signature, signature, IntSets.EMPTY_SET));
  }

  private void reserveMethodSignature(
      DexMethodSignature newMethodSignature,
      DexMethodSignature originalMethodSignature,
      IntSet removedParameterIndices) {
    // Record that methods with the given signature and removed parameters should be mapped to the
    // new signature.
    newMethodSignatures
        .computeIfAbsent(originalMethodSignature, ignoreKey(HashMap::new))
        .put(removedParameterIndices, newMethodSignature);

    // Record that the new method signature is used, by a method with the old signature that had the
    // given removed parameters.
    occupiedMethodSignatures.put(
        newMethodSignature, new Pair<>(removedParameterIndices, originalMethodSignature));
  }

  private void computeRemovableVirtualMethodParameters(
      Set<DexProgramClass> stronglyConnectedProgramClasses) {
    // Group the virtual methods in the component by their signatures.
    Map<DexMethodSignature, ProgramMethodSet> virtualMethodsBySignature =
        computeVirtualMethodsBySignature(stronglyConnectedProgramClasses);
    virtualMethodsBySignature.forEach(
        (signature, methods) -> {
          // Check that there are no keep rules that prohibit parameter removal from any of the
          // methods.
          if (Iterables.any(methods, method -> !isParameterRemovalAllowed(method))) {
            return;
          }

          // Find the parameters that are constant or unused in all methods.
          IntSet removableVirtualMethodParametersInAllMethods = new IntArraySet();
          for (int parameterIndex = 1;
              parameterIndex < signature.getProto().getArity() + 1;
              parameterIndex++) {
            if (canRemoveParameterFromVirtualMethods(parameterIndex, methods)) {
              removableVirtualMethodParametersInAllMethods.add(parameterIndex);
            }
          }

          // If any parameters could be removed, record it.
          if (!removableVirtualMethodParametersInAllMethods.isEmpty()) {
            removableVirtualMethodParameters.put(
                signature, removableVirtualMethodParametersInAllMethods);
          }
        });
  }

  private Map<DexMethodSignature, ProgramMethodSet> computeVirtualMethodsBySignature(
      Set<DexProgramClass> stronglyConnectedProgramClasses) {
    Map<DexMethodSignature, ProgramMethodSet> virtualMethodsBySignature = new HashMap<>();
    for (DexProgramClass clazz : stronglyConnectedProgramClasses) {
      clazz.forEachProgramVirtualMethod(
          method ->
              virtualMethodsBySignature
                  .computeIfAbsent(method.getMethodSignature(), ignoreKey(ProgramMethodSet::create))
                  .add(method));
    }
    return virtualMethodsBySignature;
  }

  private boolean isParameterRemovalAllowed(ProgramMethod method) {
    return appView.getKeepInfo(method).isParameterRemovalAllowed(options)
        && !method.getDefinition().isLibraryMethodOverride().isPossiblyTrue();
  }

  private boolean canRemoveParameterFromVirtualMethods(
      int parameterIndex, ProgramMethodSet methods) {
    for (ProgramMethod method : methods) {
      if (method.getDefinition().isAbstract()) {
        DexProgramClass holder = method.getHolder();
        if (holder.isInterface()) {
          ObjectAllocationInfoCollection objectAllocationInfoCollection =
              appView.appInfo().getObjectAllocationInfoCollection();
          if (objectAllocationInfoCollection.isImmediateInterfaceOfInstantiatedLambda(holder)) {
            return false;
          }
        }
        // OK, this parameter can be removed.
        continue;
      }
      CallSiteOptimizationInfo optimizationInfo =
          method.getDefinition().getCallSiteOptimizationInfo();
      if (optimizationInfo.isConcreteCallSiteOptimizationInfo()) {
        ConcreteCallSiteOptimizationInfo concreteOptimizationInfo =
            optimizationInfo.asConcreteCallSiteOptimizationInfo();
        AbstractValue abstractValue =
            concreteOptimizationInfo.getAbstractArgumentValue(parameterIndex);
        if (abstractValue.isSingleValue()
            && abstractValue.asSingleValue().isMaterializableInContext(appView, method)) {
          // OK, this parameter has a constant value and can be removed.
          continue;
        }
      }
      return false;
    }
    return true;
  }

  private void visitClass(
      DexProgramClass clazz, ArgumentPropagatorGraphLens.Builder partialGraphLensBuilder) {
    clazz.forEachProgramMethod(
        method -> {
          ArgumentInfoCollection removableParameters =
              method.getDefinition().belongsToDirectPool()
                  ? computeRemovableParametersFromDirectMethod(method)
                  : computeRemovableParametersFromVirtualMethod(method);
          DexMethod newMethodSignature = getNewMethodSignature(method, removableParameters);
          partialGraphLensBuilder.recordMove(
              method.getReference(), newMethodSignature, removableParameters);
        });
  }

  private DexMethod getNewMethodSignature(
      ProgramMethod method, ArgumentInfoCollection removableParameters) {
    DexMethodSignature methodSignatureWithoutParametersRemoved = method.getMethodSignature();
    IntSet removableParameterIndices = removableParameters.getKeys();

    // Check if there is a reserved signature for this already.
    DexMethodSignature reservedSignature =
        newMethodSignatures
            .getOrDefault(methodSignatureWithoutParametersRemoved, Collections.emptyMap())
            .get(removableParameterIndices);
    if (reservedSignature != null) {
      return reservedSignature.withHolder(method.getHolderType(), dexItemFactory);
    }

    DexMethod methodReferenceWithParametersRemoved =
        removableParameters.rewriteMethod(method, dexItemFactory);
    DexMethodSignature methodSignatureWithParametersRemoved =
        methodReferenceWithParametersRemoved.getSignature();

    // Find a method signature. First check if the current signature is available.
    if (!occupiedMethodSignatures.containsKey(methodSignatureWithParametersRemoved)) {
      reserveMethodSignature(
          methodSignatureWithParametersRemoved,
          methodSignatureWithoutParametersRemoved,
          removableParameterIndices);
      return methodReferenceWithParametersRemoved;
    }

    Pair<IntSet, DexMethodSignature> occupant =
        occupiedMethodSignatures.get(methodSignatureWithParametersRemoved);
    // In this case we should have found a reserved method signature above.
    assert !(occupant.getFirst().equals(removableParameterIndices)
        && occupant.getSecond().equals(methodSignatureWithoutParametersRemoved));

    // We need to find a new name for this method, since the signature is already occupied.
    // TODO(b/190154391): Instead of generating a new name, we could also try permuting the order of
    //  parameters.
    DexMethod newMethod =
        dexItemFactory.createFreshMethodNameWithoutHolder(
            method.getName().toString(),
            methodReferenceWithParametersRemoved.getProto(),
            method.getHolderType(),
            candidate -> {
              Pair<IntSet, DexMethodSignature> candidateOccupant =
                  occupiedMethodSignatures.get(candidate.getSignature());
              if (candidateOccupant == null) {
                return true;
              }
              return candidateOccupant.getFirst().equals(removableParameterIndices)
                  && candidateOccupant.getSecond().equals(methodSignatureWithoutParametersRemoved);
            });

    // Reserve the newly generated method signature.
    reserveMethodSignature(
        newMethod.getSignature(),
        methodSignatureWithoutParametersRemoved,
        removableParameterIndices);

    return newMethod;
  }

  private ArgumentInfoCollection computeRemovableParametersFromDirectMethod(ProgramMethod method) {
    assert method.getDefinition().belongsToDirectPool();
    if (method.getDefinition().isInstanceInitializer()) {
      // TODO(b/190154391): Allow parameter removal from initializers. We need to guarantee absence
      //  of collisions since initializers can't be renamed.
      return ArgumentInfoCollection.empty();
    }
    return computeRemovableParametersFromMethod(method);
  }

  private ArgumentInfoCollection computeRemovableParametersFromVirtualMethod(ProgramMethod method) {
    IntSet removableParameterIndices =
        removableVirtualMethodParameters.getOrDefault(
            method.getMethodSignature(), IntSets.EMPTY_SET);
    if (removableParameterIndices.isEmpty()) {
      return ArgumentInfoCollection.empty();
    }

    if (method.getAccessFlags().isAbstract()) {
      // Treat the parameters as unused.
      ArgumentInfoCollection.Builder removableParametersBuilder = ArgumentInfoCollection.builder();
      for (int removableParameterIndex : removableParameterIndices) {
        removableParametersBuilder.addArgumentInfo(
            removableParameterIndex,
            RemovedArgumentInfo.builder()
                .setType(method.getArgumentType(removableParameterIndex))
                .build());
      }
      return removableParametersBuilder.build();
    }

    ArgumentInfoCollection removableParameters =
        computeRemovableParametersFromMethod(method, removableParameterIndices::contains);
    assert removableParameters.size() == removableParameterIndices.size();
    return removableParameters;
  }

  private ArgumentInfoCollection computeRemovableParametersFromMethod(ProgramMethod method) {
    return computeRemovableParametersFromMethod(method, parameterIndex -> true);
  }

  private ArgumentInfoCollection computeRemovableParametersFromMethod(
      ProgramMethod method, IntPredicate removableParameterIndices) {
    ConcreteCallSiteOptimizationInfo optimizationInfo =
        method.getDefinition().getCallSiteOptimizationInfo().asConcreteCallSiteOptimizationInfo();
    if (optimizationInfo == null) {
      return ArgumentInfoCollection.empty();
    }

    ArgumentInfoCollection.Builder removableParametersBuilder = ArgumentInfoCollection.builder();
    for (int argumentIndex = method.getDefinition().getFirstNonReceiverArgumentIndex();
        argumentIndex < method.getDefinition().getNumberOfArguments();
        argumentIndex++) {
      if (!removableParameterIndices.test(argumentIndex)) {
        continue;
      }
      AbstractValue abstractValue = optimizationInfo.getAbstractArgumentValue(argumentIndex);
      if (abstractValue.isSingleValue()
          && abstractValue.asSingleValue().isMaterializableInContext(appView, method)) {
        removableParametersBuilder.addArgumentInfo(
            argumentIndex,
            RemovedArgumentInfo.builder()
                .setSingleValue(abstractValue.asSingleValue())
                .setType(method.getArgumentType(argumentIndex))
                .build());
      }
    }
    return removableParametersBuilder.build();
  }
}