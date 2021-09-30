// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.compilerapi;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class CompilerApiTestCollectionTest extends TestBase {

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withNoneRuntime().build();
  }

  public CompilerApiTestCollectionTest(TestParameters parameters) {
    parameters.assertNoneRuntime();
  }

  /**
   * If this test fails the API has changed in a non-compatible way. Likely the changes to the API
   * will need to be undone/changed to preserve compatibility.
   */
  @Test
  public void runCheckedInTests() throws Exception {
    new CompilerApiTestCollection(temp).runJunitOnCheckedInJar();
  }

  /**
   * If this test fails the test.jar needs to be regenerated and uploaded to cloud storage.
   *
   * <p>See: {@code CompilerApiTestCollection.main} to regenerate.
   *
   * <p>To preserve compatibility, make sure only to regenerate together with test changes and with
   * NO changes to the compiler itself.
   */
  @Test
  public void testCheckedInJarIsUpToDate() throws Exception {
    new CompilerApiTestCollection(temp).verifyCheckedInJarIsUpToDate();
  }
}
