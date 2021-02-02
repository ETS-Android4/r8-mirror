// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.regress.b76025099.testclasses.impl;

public class Factory {
  public static Impl getImpl(String name) {
    return new Impl(name);
  }
}