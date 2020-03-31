package com.android.tools.r8.graph;

import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.IterableUtils;
import com.android.tools.r8.utils.TraversalContinuation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class MethodCollection {

  // Threshold between using an array and a map for the backing store.
  // Compiling R8 plus library shows classes with up to 30 methods account for about 95% of classes.
  private static final int ARRAY_BACKING_THRESHOLD = 30;

  private final DexClass holder;
  private final MethodCollectionBacking backing;
  private DexEncodedMethod cachedClassInitializer = DexEncodedMethod.SENTINEL;

  public MethodCollection(
      DexClass holder, DexEncodedMethod[] directMethods, DexEncodedMethod[] virtualMethods) {
    this.holder = holder;
    if (directMethods.length + virtualMethods.length > ARRAY_BACKING_THRESHOLD) {
      backing = new MethodMapBacking();
    } else {
      backing = new MethodArrayBacking();
    }
    backing.setDirectMethods(directMethods);
    backing.setVirtualMethods(virtualMethods);
  }

  private void resetCaches() {
    resetDirectMethodCaches();
    resetVirtualMethodCaches();
  }

  private void resetDirectMethodCaches() {
    resetClassInitializerCache();
  }

  private void resetVirtualMethodCaches() {
    // Nothing to do.
  }

  public int size() {
    return backing.size();
  }

  public TraversalContinuation traverse(Function<DexEncodedMethod, TraversalContinuation> fn) {
    return backing.traverse(fn);
  }

  public void forEachMethod(Consumer<DexEncodedMethod> consumer) {
    backing.forEachMethod(consumer);
  }

  public Iterable<DexEncodedMethod> methods() {
    return backing.methods();
  }

  public Iterable<DexEncodedMethod> methods(Predicate<DexEncodedMethod> predicate) {
    return IterableUtils.filter(methods(), predicate);
  }

  public List<DexEncodedMethod> allMethodsSorted() {
    List<DexEncodedMethod> sorted = new ArrayList<>(size());
    forEachMethod(sorted::add);
    sorted.sort((a, b) -> a.method.slowCompareTo(b.method));
    return sorted;
  }

  public List<DexEncodedMethod> directMethods() {
    if (InternalOptions.assertionsEnabled()) {
      return Collections.unmodifiableList(backing.directMethods());
    }
    return backing.directMethods();
  }

  public List<DexEncodedMethod> virtualMethods() {
    if (InternalOptions.assertionsEnabled()) {
      return Collections.unmodifiableList(backing.virtualMethods());
    }
    return backing.virtualMethods();
  }

  public DexEncodedMethod getMethod(DexMethod method) {
    return backing.getMethod(method);
  }

  public DexEncodedMethod getDirectMethod(DexMethod method) {
    return backing.getDirectMethod(method);
  }

  public DexEncodedMethod getDirectMethod(Predicate<DexEncodedMethod> predicate) {
    return backing.getDirectMethod(predicate);
  }

  public DexEncodedMethod getVirtualMethod(DexMethod method) {
    return backing.getVirtualMethod(method);
  }

  public DexEncodedMethod getVirtualMethod(Predicate<DexEncodedMethod> predicate) {
    return backing.getVirtualMethod(predicate);
  }

  private void resetClassInitializerCache() {
    cachedClassInitializer = DexEncodedMethod.SENTINEL;
  }

  public DexEncodedMethod getClassInitializer() {
    if (cachedClassInitializer == DexEncodedMethod.SENTINEL) {
      cachedClassInitializer = null;
      for (DexEncodedMethod directMethod : directMethods()) {
        if (directMethod.isClassInitializer()) {
          cachedClassInitializer = directMethod;
          break;
        }
      }
    }
    return cachedClassInitializer;
  }

  public void addMethod(DexEncodedMethod method) {
    resetCaches();
    backing.addMethod(method);
  }

  public void addVirtualMethod(DexEncodedMethod virtualMethod) {
    resetVirtualMethodCaches();
    backing.addVirtualMethod(virtualMethod);
  }

  public void addDirectMethod(DexEncodedMethod directMethod) {
    resetDirectMethodCaches();
    backing.addDirectMethod(directMethod);
  }

  public DexEncodedMethod replaceDirectMethod(
      DexMethod method, Function<DexEncodedMethod, DexEncodedMethod> replacement) {
    resetDirectMethodCaches();
    return backing.replaceDirectMethod(method, replacement);
  }

  public void replaceMethods(Function<DexEncodedMethod, DexEncodedMethod> replacement) {
    resetCaches();
    backing.replaceMethods(replacement);
  }

  public void replaceVirtualMethods(Function<DexEncodedMethod, DexEncodedMethod> replacement) {
    resetVirtualMethodCaches();
    backing.replaceVirtualMethods(replacement);
  }

  public void replaceDirectMethods(Function<DexEncodedMethod, DexEncodedMethod> replacement) {
    resetDirectMethodCaches();
    backing.replaceDirectMethods(replacement);
  }

  /**
   * Replace a direct method, if found, by a computed virtual method using the replacement function.
   *
   * @param method Direct method to replace if present.
   * @param replacement Replacement function computing the virtual replacement.
   * @return Returns the replacement if found, null otherwise.
   */
  public DexEncodedMethod replaceDirectMethodWithVirtualMethod(
      DexMethod method, Function<DexEncodedMethod, DexEncodedMethod> replacement) {
    resetCaches();
    return backing.replaceDirectMethodWithVirtualMethod(method, replacement);
  }

  public void addDirectMethods(Collection<DexEncodedMethod> methods) {
    assert verifyCorrectnessOfMethodHolders(methods);
    resetDirectMethodCaches();
    backing.addDirectMethods(methods);
  }

  public void removeDirectMethod(DexMethod method) {
    resetDirectMethodCaches();
    backing.removeDirectMethod(method);
  }

  public void setDirectMethods(DexEncodedMethod[] methods) {
    assert verifyCorrectnessOfMethodHolders(methods);
    resetDirectMethodCaches();
    backing.setDirectMethods(methods);
  }

  public void addVirtualMethods(Collection<DexEncodedMethod> methods) {
    assert verifyCorrectnessOfMethodHolders(methods);
    resetVirtualMethodCaches();
    backing.addVirtualMethods(methods);
  }

  public void setVirtualMethods(DexEncodedMethod[] methods) {
    assert verifyCorrectnessOfMethodHolders(methods);
    resetVirtualMethodCaches();
    backing.setVirtualMethods(methods);
  }

  public void virtualizeMethods(Set<DexEncodedMethod> privateInstanceMethods) {
    resetVirtualMethodCaches();
    backing.virtualizeMethods(privateInstanceMethods);
  }

  public boolean hasAnnotations() {
    return traverse(
            method ->
                method.hasAnnotation()
                    ? TraversalContinuation.BREAK
                    : TraversalContinuation.CONTINUE)
        .shouldBreak();
  }

  public boolean verify() {
    forEachMethod(
        method -> {
          assert verifyCorrectnessOfMethodHolder(method);
        });
    assert backing.verify();
    return true;
  }

  private boolean verifyCorrectnessOfMethodHolder(DexEncodedMethod method) {
    assert method.holder() == holder.type
        : "Expected method `"
            + method.method.toSourceString()
            + "` to have holder `"
            + holder.type.toSourceString()
            + "`";
    return true;
  }

  private boolean verifyCorrectnessOfMethodHolders(DexEncodedMethod[] methods) {
    if (methods == null) {
      return true;
    }
    return verifyCorrectnessOfMethodHolders(Arrays.asList(methods));
  }

  private boolean verifyCorrectnessOfMethodHolders(Iterable<DexEncodedMethod> methods) {
    for (DexEncodedMethod method : methods) {
      assert verifyCorrectnessOfMethodHolder(method);
    }
    return true;
  }
}
