// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tools.r8.StringResource;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.ToolHelper;
import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.graph.AppInfoWithSubtyping;
import com.android.tools.r8.graph.AppServices;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DirectMappedDexApplication;
import com.android.tools.r8.graph.LookupResult;
import com.android.tools.r8.graph.LookupResult.LookupResultSuccess;
import com.android.tools.r8.graph.ResolutionResult;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.Timing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Test;

public class R8GMSCoreLookupTest extends TestBase {

  private static final String APP_DIR = "third_party/gmscore/v5/";
  private DirectMappedDexApplication program;
  private AppView<? extends AppInfoWithSubtyping> appView;

  @Before
  public void readGMSCore() throws Exception {
    Path directory = Paths.get(APP_DIR);
    AndroidApp app = ToolHelper.builderFromProgramDirectory(directory).build();
    Path mapFile = directory.resolve(ToolHelper.DEFAULT_PROGUARD_MAP_FILE);
    StringResource proguardMap = null;
    if (Files.exists(mapFile)) {
      proguardMap = StringResource.fromFile(mapFile);
    }
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    Timing timing = Timing.empty();
    program =
        new ApplicationReader(app, new InternalOptions(), timing)
            .read(proguardMap, executorService)
            .toDirect();
    InternalOptions options = new InternalOptions();
    appView = AppView.createForR8(new AppInfoWithSubtyping(program), options);
    appView.setAppServices(AppServices.builder(appView).build());
  }

  private AppInfoWithSubtyping appInfo() {
    return appView.appInfo();
  }

  private void testVirtualLookup(DexProgramClass clazz, DexEncodedMethod method) {
    // Check lookup will produce the same result.
    DexMethod id = method.method;
    assertEquals(appInfo().resolveMethod(id.holder, method.method).getSingleTarget(), method);

    // Check lookup targets with include method.
    ResolutionResult resolutionResult = appInfo().resolveMethodOnClass(clazz, method.method);
    LookupResult lookupResult =
        resolutionResult.lookupVirtualDispatchTargets(
            clazz, appInfo(), appInfo(), dexReference -> false);
    assertTrue(lookupResult.isLookupResultSuccess());
    assertTrue(lookupResult.asLookupResultSuccess().contains(method));
  }

  private static class Counter {
    int count = 0;

    void inc() {
      count++;
    }
  }

  private void testInterfaceLookup(DexProgramClass clazz, DexEncodedMethod method) {
    LookupResultSuccess lookupResult =
        appInfo()
            .resolveMethodOnInterface(clazz, method.method)
            .lookupVirtualDispatchTargets(clazz, appInfo(), appInfo(), dexReference -> false)
            .asLookupResultSuccess();
    assertNotNull(lookupResult);
    assertFalse(lookupResult.hasLambdaTargets());
    if (appInfo().subtypes(method.holder()).stream()
        .allMatch(t -> appInfo().definitionFor(t).isInterface())) {
      Counter counter = new Counter();
      lookupResult.forEach(
          target -> {
            DexEncodedMethod m = target.getMethod();
            if (m.accessFlags.isAbstract() || !m.accessFlags.isBridge()) {
              counter.inc();
            }
          },
          l -> fail());
      assertEquals(0, counter.count);
    } else {
      Counter counter = new Counter();
      lookupResult.forEach(
          target -> {
            if (target.getMethod().isAbstract()) {
              counter.inc();
            }
          },
          lambda -> fail());
      assertEquals(0, counter.count);
    }
  }

  private void testLookup(DexProgramClass clazz) {
    if (clazz.isInterface()) {
      for (DexEncodedMethod method : clazz.virtualMethods()) {
        testInterfaceLookup(clazz, method);
      }
    } else {
      for (DexEncodedMethod method : clazz.virtualMethods()) {
        testVirtualLookup(clazz, method);
      }
    }
  }

  @Test
  public void testLookup() {
    program.classesWithDeterministicOrder().forEach(this::testLookup);
  }
}
