// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.FieldAccessInfoCollectionImpl;
import com.android.tools.r8.graph.FieldAccessInfoImpl;
import com.google.common.collect.Sets;
import java.util.Set;

/** Used to mutate AppInfoWithLiveness between waves. */
public class AppInfoWithLivenessModifier {

  private final Set<DexProgramClass> noLongerInstantiatedClasses = Sets.newConcurrentHashSet();
  private final Set<DexReference> noLongerPinnedItems = Sets.newConcurrentHashSet();
  private final Set<DexField> noLongerWrittenFields = Sets.newConcurrentHashSet();

  AppInfoWithLivenessModifier() {}

  public boolean isEmpty() {
    return noLongerInstantiatedClasses.isEmpty();
  }

  public void removeInstantiatedType(DexProgramClass clazz) {
    noLongerInstantiatedClasses.add(clazz);
  }

  public void removePinnedClassMembers(DexProgramClass clazz) {
    clazz.members().forEach(member -> noLongerPinnedItems.add(member.toReference()));
  }

  public void removeWrittenField(DexField field) {
    noLongerWrittenFields.add(field);
  }

  public void modify(AppInfoWithLiveness appInfo) {
    // Instantiated classes.
    noLongerInstantiatedClasses.forEach(appInfo::removeFromSingleTargetLookupCache);
    appInfo.mutateObjectAllocationInfoCollection(
        mutator -> noLongerInstantiatedClasses.forEach(mutator::markNoLongerInstantiated));
    // Pinned items.
    noLongerPinnedItems.forEach(appInfo::removePinnedItem);
    // Written fields.
    FieldAccessInfoCollectionImpl fieldAccessInfoCollection =
        appInfo.getMutableFieldAccessInfoCollection();
    noLongerWrittenFields.forEach(
        field -> {
          FieldAccessInfoImpl fieldAccessInfo = fieldAccessInfoCollection.get(field);
          if (fieldAccessInfo != null) {
            fieldAccessInfo.clearWrites();
          }
        });

    clear();
  }

  private void clear() {
    noLongerInstantiatedClasses.clear();
  }
}
