/*
 * Copyright (c) 2014, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.dispatch;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.utilities.BranchProfile;

import org.jruby.truffle.nodes.RubyGuards;
import org.jruby.truffle.nodes.methods.DeclarationContext;
import org.jruby.truffle.runtime.RubyArguments;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.core.StringOperations;
import org.jruby.truffle.runtime.layouts.Layouts;
import org.jruby.truffle.runtime.methods.InternalMethod;

public abstract class CachedDispatchNode extends DispatchNode {

    private final Object cachedName;
    private final DynamicObject cachedNameAsSymbol;

    @Child protected DispatchNode next;

    private final BranchProfile moreThanReferenceCompare = BranchProfile.create();

    public CachedDispatchNode(
            RubyContext context,
            Object cachedName,
            DispatchNode next,
            DispatchAction dispatchAction) {
        super(context, dispatchAction);

        assert (cachedName instanceof String) || (RubyGuards.isRubySymbol(cachedName)) || (RubyGuards.isRubyString(cachedName));
        this.cachedName = cachedName;

        if (RubyGuards.isRubySymbol(cachedName)) {
            cachedNameAsSymbol = (DynamicObject) cachedName;
        } else if (RubyGuards.isRubyString(cachedName)) {
            cachedNameAsSymbol = context.getSymbol(StringOperations.getByteList((DynamicObject) cachedName));
        } else if (cachedName instanceof String) {
            cachedNameAsSymbol = context.getSymbol((String) cachedName);
        } else {
            throw new UnsupportedOperationException();
        }

        this.next = next;
    }

    @Override
    protected DispatchNode getNext() {
        return next;
    }

    protected final boolean guardName(Object methodName) {
        if (cachedName == methodName) {
            return true;
        }

        moreThanReferenceCompare.enter();

        if (cachedName instanceof String) {
            return cachedName.equals(methodName);
        } else if (RubyGuards.isRubySymbol(cachedName)) {
            // TODO(CS, 11-Jan-15) this just repeats the above guard...
            return cachedName == methodName;
        } else if (RubyGuards.isRubyString(cachedName)) {
            return (RubyGuards.isRubyString(methodName)) && StringOperations.getByteList((DynamicObject) cachedName).equal(StringOperations.getByteList((DynamicObject) methodName));
        } else {
            throw new UnsupportedOperationException();
        }
    }

    protected DynamicObject getCachedNameAsSymbol() {
        return cachedNameAsSymbol;
    }

    protected void applySplittingInliningStrategy(DirectCallNode callNode, InternalMethod method) {
        if (callNode.isCallTargetCloningAllowed() && method.getSharedMethodInfo().shouldAlwaysClone()) {
            insert(callNode);
            callNode.cloneCallTarget();
        }

        if (method.getSharedMethodInfo().shouldAlwaysInline() && callNode.isInlinable()) {
            callNode.forceInlining();
        }
    }

    protected static Object call(DirectCallNode callNode, VirtualFrame frame, InternalMethod method, Object receiver, DynamicObject block, Object[] arguments) {
        CompilerAsserts.compilationConstant(method.getSharedMethodInfo().needsCallerFrame());

        return callNode.call(
                frame,
                RubyArguments.pack(
                        method,
                        null,
                        method.getSharedMethodInfo().needsCallerFrame() ? frame.materialize() : null,
                        receiver,
                        block,
                        DeclarationContext.METHOD,
                        arguments));
    }
}
