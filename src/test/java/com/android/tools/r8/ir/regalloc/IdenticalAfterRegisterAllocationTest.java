// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.regalloc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.ir.code.Add;
import com.android.tools.r8.ir.code.ConstNumber;
import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import org.junit.Test;

public class IdenticalAfterRegisterAllocationTest {

  private static class MockRegisterAllocator implements RegisterAllocator {
    @Override
    public void allocateRegisters(boolean debug) {
    }

    @Override
    public int registersUsed() {
      return 0;
    }

    @Override
    public int getRegisterForValue(Value value, int instructionNumber) {
      // Use the value number as the register number.
      return value.getNumber();
    }

    @Override
    public boolean argumentValueUsesHighRegister(Value value, int instructionNumber) {
      return false;
    }

    @Override
    public int getArgumentOrAllocateRegisterForValue(Value value, int instructionNumber) {
      return value.getNumber();
    }
  }

  @Test
  public void equalityOfConstantOperands() {
    RegisterAllocator allocator = new MockRegisterAllocator();
    Value value0 = new Value(0, ValueType.INT, null);
    ConstNumber const0 = new ConstNumber(value0, 0);
    Value value1 = new Value(1, ValueType.INT, null);
    ConstNumber const1 = new ConstNumber(value1, 1);
    Value value2 = new Value(2, ValueType.INT, null);
    ConstNumber const2 = new ConstNumber(value2, 2);
    Value value3 = new Value(2, ValueType.INT, null);
    Add add0 = new Add(NumericType.INT, value3, value0, value1);
    add0.setPosition(Position.none());
    Add add1 = new Add(NumericType.INT, value3, value0, value2);
    add1.setPosition(Position.none());
    value0.computeNeedsRegister();
    assertTrue(value0.needsRegister());
    value1.computeNeedsRegister();
    assertFalse(value1.needsRegister());
    value2.computeNeedsRegister();
    assertFalse(value2.needsRegister());
    value3.computeNeedsRegister();
    assertTrue(value3.needsRegister());
    // value1 and value2 represent different constants and the additions are therefore
    // not equivalent.
    assertFalse(add0.identicalAfterRegisterAllocation(add1, allocator));
  }
}
