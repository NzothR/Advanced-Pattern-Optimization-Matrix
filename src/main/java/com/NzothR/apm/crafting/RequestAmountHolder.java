package com.NzothR.apm.crafting;

import java.util.ArrayDeque;
import java.util.Deque;

import com.NzothR.apm.AdvancedPatternMatrixMod;

/**
 * ThreadLocal stack for crafting request amount propagation.
 */
public final class RequestAmountHolder {

    private static final ThreadLocal<Deque<Long>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private RequestAmountHolder() {}

    public static void push(long amount) {
        STACK.get()
            .push(amount);
    }

    public static void pop() {
        Deque<Long> st = STACK.get();
        if (!st.isEmpty()) {
            st.pop();
        } else {
            AdvancedPatternMatrixMod.LOG.warn("[APM] pop() called on empty stack — push/pop mismatch!");
        }
        if (st.isEmpty()) STACK.remove();
    }

    public static long peek() {
        Deque<Long> st = STACK.get();
        return st.isEmpty() ? 1L : st.peek();
    }

    public static int depth() {
        return STACK.get()
            .size();
    }
}
