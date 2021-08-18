// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.ir.desugar.itf.EmulatedInterfaceSynthesizer;
import com.android.tools.r8.utils.ThreadUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class CfL8ClassSynthesizerCollection {

  private Collection<CfL8ClassSynthesizer> synthesizers = new ArrayList<>();

  public CfL8ClassSynthesizerCollection(AppView<?> appView) {
    assert appView.options().isDesugaredLibraryCompilation();
    EmulatedInterfaceSynthesizer emulatedInterfaceSynthesizer =
        EmulatedInterfaceSynthesizer.create(appView);
    if (emulatedInterfaceSynthesizer != null) {
      synthesizers.add(emulatedInterfaceSynthesizer);
    }
  }

  public void synthesizeClasses(
      ExecutorService executorService, CfL8ClassSynthesizerEventConsumer eventConsumer)
      throws ExecutionException {
    ArrayList<Future<?>> futures = new ArrayList<>();
    for (CfL8ClassSynthesizer synthesizer : synthesizers) {
      futures.addAll(synthesizer.synthesizeClasses(executorService, eventConsumer));
    }
    ThreadUtils.awaitFutures(futures);
  }
}