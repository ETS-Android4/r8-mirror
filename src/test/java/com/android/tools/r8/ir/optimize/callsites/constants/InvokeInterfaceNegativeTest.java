// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize.callsites.constants;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NoVerticalClassMerging;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.optimize.info.CallSiteOptimizationInfo;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class InvokeInterfaceNegativeTest extends TestBase {

  private static final Class<?> MAIN = Main.class;

  @Parameters(name = "{1}, experimental: {0}")
  public static List<Object[]> data() {
    return buildParameters(
        BooleanUtils.values(), getTestParameters().withAllRuntimesAndApiLevels().build());
  }

  private final boolean enableExperimentalArgumentPropagation;
  private final TestParameters parameters;

  public InvokeInterfaceNegativeTest(
      boolean enableExperimentalArgumentPropagation, TestParameters parameters) {
    this.enableExperimentalArgumentPropagation = enableExperimentalArgumentPropagation;
    this.parameters = parameters;
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(InvokeInterfaceNegativeTest.class)
        .addKeepMainRule(MAIN)
        .enableNoVerticalClassMergingAnnotations()
        .enableNeverClassInliningAnnotations()
        .enableInliningAnnotations()
        .addOptionsModification(
            o -> {
              // To prevent invoke-interface from being rewritten to invoke-virtual w/ a single
              // target.
              o.enableDevirtualization = false;
              o.testing.callSiteOptimizationInfoInspector = this::callSiteOptimizationInfoInspect;
              o.callSiteOptimizationOptions()
                  .setEnableExperimentalArgumentPropagation(enableExperimentalArgumentPropagation);
            })
        .setMinApi(parameters.getApiLevel())
        .run(parameters.getRuntime(), MAIN)
        .assertSuccessWithOutputLines("null", "non-null")
        .inspect(this::inspect);
  }

  private void callSiteOptimizationInfoInspect(ProgramMethod method) {
    assert method.getReference().name.toString().equals("m")
        : "Unexpected revisit: " + method.toSourceString();
    CallSiteOptimizationInfo callSiteOptimizationInfo =
        method.getDefinition().getCallSiteOptimizationInfo();
    assert callSiteOptimizationInfo.getDynamicUpperBoundType(1).isDefinitelyNotNull();
    assert callSiteOptimizationInfo.getAbstractArgumentValue(1).isUnknown();
  }

  private void inspect(CodeInspector inspector) {
    ClassSubject i = inspector.clazz(I.class);
    assertThat(i, isPresent());
    ClassSubject a = inspector.clazz(A.class);
    assertThat(a, isPresent());
    MethodSubject a_m = a.uniqueMethodWithName("m");
    assertThat(a_m, isPresent());
    // Should not optimize branches since the value of `arg` is unsure.
    assertTrue(a_m.streamInstructions().anyMatch(InstructionSubject::isIf));
  }

  @NoVerticalClassMerging
  interface I {
    void m(String arg);
  }

  @NeverClassInline
  static class A implements I {
    @NeverInline
    @Override
    public void m(String arg) {
      if (arg.contains("null")) {
        System.out.println("null");
      } else {
        System.out.println("non-null");
      }
    }
  }

  static class Main {
    public static void main(String... args) {
      I i = new A();
      i.m("null");  // calls A.m() with "null".
      i.m("nul");   // calls A.m() with "nul".
    }
  }
}
