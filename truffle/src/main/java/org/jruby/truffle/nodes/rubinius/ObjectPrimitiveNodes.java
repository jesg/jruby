/*
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.rubinius;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.utilities.ConditionProfile;
import org.jruby.truffle.nodes.objects.IsTaintedNode;
import org.jruby.truffle.nodes.objects.IsTaintedNodeGen;
import org.jruby.truffle.nodes.objects.TaintNode;
import org.jruby.truffle.nodes.objects.TaintNodeGen;
import org.jruby.truffle.nodes.objectstorage.ReadHeadObjectFieldNode;
import org.jruby.truffle.nodes.objectstorage.ReadHeadObjectFieldNodeGen;
import org.jruby.truffle.nodes.objectstorage.WriteHeadObjectFieldNode;
import org.jruby.truffle.nodes.objectstorage.WriteHeadObjectFieldNodeGen;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.layouts.Layouts;
import org.jruby.truffle.runtime.object.ObjectIDOperations;

/**
 * Rubinius primitives associated with the Ruby {@code Object} class.
 */
public abstract class ObjectPrimitiveNodes {

    @RubiniusPrimitive(name = "object_id")
    public abstract static class ObjectIDPrimitiveNode extends RubiniusPrimitiveNode {

        public ObjectIDPrimitiveNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public abstract Object executeObjectID(VirtualFrame frame, Object value);

        @Specialization(guards = "isNil(nil)")
        public long objectID(Object nil) {
            return ObjectIDOperations.NIL;
        }

        @Specialization(guards = "value")
        public long objectIDTrue(boolean value) {
            return ObjectIDOperations.TRUE;
        }

        @Specialization(guards = "!value")
        public long objectIDFalse(boolean value) {
            return ObjectIDOperations.FALSE;
        }

        @Specialization
        public long objectID(int value) {
            return ObjectIDOperations.smallFixnumToID(value);
        }

        @Specialization(rewriteOn = ArithmeticException.class)
        public long objectIDSmallFixnumOverflow(long value) throws ArithmeticException {
            return ObjectIDOperations.smallFixnumToIDOverflow(value);
        }

        @Specialization
        public Object objectID(long value,
                               @Cached("createCountingProfile()") ConditionProfile smallProfile) {
            if (smallProfile.profile(ObjectIDOperations.isSmallFixnum(value))) {
                return ObjectIDOperations.smallFixnumToID(value);
            } else {
                return ObjectIDOperations.largeFixnumToID(getContext(), value);
            }
        }

        @Specialization
        public Object objectID(double value) {
            return ObjectIDOperations.floatToID(getContext(), value);
        }

        @Specialization(guards = "!isNil(object)")
        public long objectID(DynamicObject object,
                @Cached("createReadObjectIDNode()") ReadHeadObjectFieldNode readObjectIdNode,
                @Cached("createWriteObjectIDNode()") WriteHeadObjectFieldNode writeObjectIdNode) {
            final long id;
            try {
                id = readObjectIdNode.executeLong(object);
            } catch (UnexpectedResultException e) {
                throw new UnsupportedOperationException(e);
            }

            if (id == 0) {
                final long newId = getContext().getNextObjectID();
                writeObjectIdNode.execute(object, newId);
                return newId;
            }

            return (long) id;
        }

        protected ReadHeadObjectFieldNode createReadObjectIDNode() {
            return ReadHeadObjectFieldNodeGen.create(Layouts.OBJECT_ID_IDENTIFIER, 0L);
        }

        protected WriteHeadObjectFieldNode createWriteObjectIDNode() {
            return WriteHeadObjectFieldNodeGen.create(Layouts.OBJECT_ID_IDENTIFIER);
        }

    }

    @RubiniusPrimitive(name = "object_infect", needsSelf = false)
    public static abstract class ObjectInfectPrimitiveNode extends RubiniusPrimitiveNode {

        @Child private IsTaintedNode isTaintedNode;
        @Child private TaintNode taintNode;
        
        public ObjectInfectPrimitiveNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public Object objectInfect(Object host, Object source) {
            if (isTaintedNode == null) {
                CompilerDirectives.transferToInterpreter();
                isTaintedNode = insert(IsTaintedNodeGen.create(getContext(), getSourceSection(), null));
            }
            
            if (isTaintedNode.executeIsTainted(source)) {
                // This lazy node allocation effectively gives us a branch profile
                
                if (taintNode == null) {
                    CompilerDirectives.transferToInterpreter();
                    taintNode = insert(TaintNodeGen.create(getContext(), getSourceSection(), null));
                }
                
                taintNode.executeTaint(host);
            }
            
            return host;
        }

    }

}
