// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.backports;

import com.android.tools.r8.NeverInline;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.utils.AndroidApiLevel;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public final class FloatBackportTest extends AbstractBackportTest {
  @Parameters(name = "{0}")
  public static Iterable<?> data() {
    return getTestParameters().withDexRuntimes().build();
  }

  public FloatBackportTest(TestParameters parameters) {
    super(parameters, Float.class, Main.class);
    registerTarget(AndroidApiLevel.N, 10);
  }

  static final class Main extends MiniAssert {
    private static final float[] interestingValues = {
        Float.MIN_VALUE, Float.MAX_VALUE,
        Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
        Integer.MIN_VALUE, Integer.MAX_VALUE,
        Long.MIN_VALUE, Long.MAX_VALUE,
        Float.NaN,
        0f,
        -1f, 1f,
        -0.5f, 0.5f,
        -0.1f, 0.1f,
    };

    public static void main(String[] args) {
      for (float value : interestingValues) {
        assertEquals(expectedHashCode(value), Float.hashCode(value));
      }

      assertTrue(Float.isFinite(0f));
      assertTrue(Float.isFinite(Float.MIN_VALUE));
      assertTrue(Float.isFinite(Float.MAX_VALUE));
      assertFalse(Float.isFinite(Float.NaN));
      assertFalse(Float.isFinite(Float.POSITIVE_INFINITY));
      assertFalse(Float.isFinite(Float.NEGATIVE_INFINITY));

      for (float x : interestingValues) {
        for (float y : interestingValues) {
          assertEquals(Math.max(x, y), Float.max(x, y));
        }
      }

      for (float x : interestingValues) {
        for (float y : interestingValues) {
          assertEquals(Math.min(x, y), Float.min(x, y));
        }
      }

      for (float x : interestingValues) {
        for (float y : interestingValues) {
          assertEquals(x + y, Float.sum(x, y));
        }
      }
    }

    @NeverInline // Avoid changing invoke counts in main().
    private static int expectedHashCode(float f) {
      return Float.valueOf(f).hashCode();
    }
  }
}
