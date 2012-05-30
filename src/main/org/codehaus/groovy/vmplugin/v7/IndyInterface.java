/*
 * Copyright 2003-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.vmplugin.v7;

import groovy.lang.AdaptingMetaClass;
import groovy.lang.GString;
import groovy.lang.GroovyObject;
import groovy.lang.GroovyRuntimeException;
import groovy.lang.GroovySystem;
import groovy.lang.MetaClass;
import groovy.lang.MetaClassImpl;
import groovy.lang.MetaClassRegistryChangeEvent;
import groovy.lang.MetaClassRegistryChangeEventListener;
import groovy.lang.MetaMethod;
import groovy.lang.MetaObjectProtocol;
import groovy.lang.MissingMethodException;

import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.reflection.CachedMethod;
import org.codehaus.groovy.runtime.NullObject;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.runtime.metaclass.MetaClassRegistryImpl;
import org.codehaus.groovy.runtime.metaclass.MissingMethodExecutionFailed;
import org.codehaus.groovy.runtime.metaclass.NewInstanceMetaMethod;
import org.codehaus.groovy.runtime.metaclass.ReflectionMetaMethod;
import org.codehaus.groovy.runtime.wrappers.Wrapper;

/**
 * Bytecode level interface for bootstrap methods used by invokedynamic.
 * 
 * @author <a href="mailto:blackdrag@gmx.org">Jochen "blackdrag" Theodorou</a>
 */
public class IndyInterface {
    
    /*
     * notes:
     *      MethodHandles#dropArguments: 
     *          invocation with (a,b,c), drop first 2 results in invocation
     *          with (a) only. 
     *      MethodHandles#insertArguments:
     *          invocation with (a,b,c), insert (x,y) results in error.
     *          first need to add with addParameters (X,Y), then bind them with 
     *          insert 
     */
    
        private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
        private static final MethodHandle SELECT_METHOD, SELECT_PROPERTY;
        static {
            MethodType mt = MethodType.methodType(Object.class, MutableCallSite.class, Class.class, String.class, Boolean.class, Boolean.class, Object.class, Object[].class);
            try {
                SELECT_METHOD = LOOKUP.findStatic(IndyInterface.class, "selectMethod", mt);
            } catch (Exception e) {
                throw new GroovyBugError(e);
            }
            mt = MethodType.methodType(Object.class, MutableCallSite.class, Class.class, String.class, int.class, Object.class);
            try {
                SELECT_PROPERTY = LOOKUP.findStatic(IndyInterface.class, "selectProperty", mt);
            } catch (Exception e) {
                throw new GroovyBugError(e);
            }
        }
        private static final MethodType GENERAL_INVOKER_SIGNATURE = MethodType.methodType(Object.class, Object.class, Object[].class);
        private static final MethodType INVOKE_METHOD_SIGNATURE = MethodType.methodType(Object.class, Class.class, Object.class, String.class, Object[].class, boolean.class, boolean.class);
        private static final MethodType O2O = MethodType.methodType(Object.class, Object.class);
        private static final MethodType S2O = MethodType.methodType(Object.class, String.class);
        private static final MethodHandle   
            UNWRAP_METHOD,  TO_STRING,          TO_BYTE,        
            TO_BIGINT,      SAME_MC,            IS_NULL,
            IS_NOT_NULL,    UNWRAP_EXCEPTION,   SAME_CLASS,
            META_METHOD_INVOKER,    GROOVY_OBJECT_INVOKER,
            GROOVY_OBJECT_GET;
        static {
            try {
                UNWRAP_METHOD = LOOKUP.findStatic(IndyInterface.class, "unwrap", O2O);
                TO_STRING = LOOKUP.findStatic(IndyInterface.class, "coerceToString", MethodType.methodType(String.class, Object.class));
                TO_BYTE = LOOKUP.findStatic(IndyInterface.class, "coerceToByte", O2O);
                TO_BIGINT = LOOKUP.findStatic(IndyInterface.class, "coerceToBigInt", O2O);
                SAME_MC = LOOKUP.findStatic(IndyInterface.class, "isSameMetaClass", MethodType.methodType(boolean.class, MetaClass.class, Object.class));
                IS_NULL = LOOKUP.findStatic(IndyInterface.class, "isNull", MethodType.methodType(boolean.class, Object.class));
                IS_NOT_NULL = LOOKUP.findStatic(IndyInterface.class, "isNotNull", MethodType.methodType(boolean.class, Object.class));
                UNWRAP_EXCEPTION = LOOKUP.findStatic(IndyInterface.class, "unwrap", MethodType.methodType(Object.class, GroovyRuntimeException.class));
                SAME_CLASS = LOOKUP.findStatic(IndyInterface.class, "sameClass", MethodType.methodType(boolean.class, Class.class, Object.class));
                META_METHOD_INVOKER = LOOKUP.findVirtual(MetaMethod.class, "invoke", GENERAL_INVOKER_SIGNATURE);
                GROOVY_OBJECT_INVOKER = LOOKUP.findStatic(IndyInterface.class, "invokeGroovyObjectInvoker", MethodType.methodType(Object.class, MissingMethodException.class, Object.class, String.class, Object[].class));
                GROOVY_OBJECT_GET = LOOKUP.findVirtual(GroovyObject.class, "getProperty", S2O);
            } catch (Exception e) {
                throw new GroovyBugError(e);
            }
        }
        private static final MethodHandle NULL_REF = MethodHandles.constant(Object.class, null);
        
        private static SwitchPoint switchPoint = new SwitchPoint();
        static {
            GroovySystem.getMetaClassRegistry().addMetaClassRegistryChangeEventListener(new MetaClassRegistryChangeEventListener() {
                public void updateConstantMetaClass(MetaClassRegistryChangeEvent cmcu) {
                    SwitchPoint old = switchPoint;
                    switchPoint = new SwitchPoint();
                    synchronized(this) { SwitchPoint.invalidateAll(new SwitchPoint[]{old}); }
                }
            });
        }

        
        // the entry points from bytecode
        
        /**
         * bootstrap method for method calls with "this" as receiver
         */
        public static CallSite bootstrapCurrent(Lookup caller, String name, MethodType type) {
            return realBootstrap(caller, name, type, false, true);
        }
        
        public static CallSite bootstrapCurrentSafe(Lookup caller, String name, MethodType type) {
            return realBootstrap(caller, name, type, true, true);
        }
        
        /**
         * bootstrap method for standard method calls
         */
        public static CallSite bootstrap(Lookup caller, String name, MethodType type) {
            return realBootstrap(caller, name, type, false, false);
        }
        
        /**
         * bootstrap method for null safe standard method calls
         */
        public static CallSite bootstrapSafe(Lookup caller, String name, MethodType type) {
            return realBootstrap(caller, name, type, true, false);
        }
        
        /**
         * backing bootstrap method with all parameters
         */
        private static CallSite realBootstrap(Lookup caller, String name, MethodType type, boolean safe, boolean thisCall) {
            // since indy does not give us the runtime types
            // we produce first a dummy call site, which then changes the target to one,
            // that does the method selection including the the direct call to the 
            // real method.
            MutableCallSite mc = new MutableCallSite(type);
            MethodHandle mh = makeMethodFallBack(mc,caller.lookupClass(),name,type,safe,thisCall);
            mc.setTarget(mh);
            return mc;            
        }
        
        /**
         * bytecode entry point for get property calls
         */
        public static CallSite bootstrapGetProperty(Lookup caller, String name, MethodType type, int callType) {
            MutableCallSite mc = new MutableCallSite(type);
            MethodHandle mh = makePropertyFallBack(mc,caller.lookupClass(),name,type,callType);
            mc.setTarget(mh);
            return mc;           
        }

        /**
         * Makes a fallback method for invalidate property selection
         */
        private static MethodHandle makePropertyFallBack(MutableCallSite mc, Class<?> sender, String name, MethodType type, int callType) {
            MethodHandle mh =   MethodHandles.insertArguments(SELECT_PROPERTY, 0, mc, sender, name, callType).
            asType(type);
            return mh;
        }
        
        /**
         * Makes a fallback method for an invalidated method selection
         */
        private static MethodHandle makeMethodFallBack(MutableCallSite mc, Class<?> sender, String name, MethodType type, boolean safeNavigation, boolean thisCall) {
            MethodHandle mh = MethodHandles.insertArguments(SELECT_METHOD, 0, mc, sender, name, safeNavigation, thisCall, /*dummy receiver:*/ 1);
            mh =    mh.asCollector(Object[].class, type.parameterCount()).
                    asType(type);
            return mh;
        }

        /**
         * Gets the class of an object.
         * In case the object is a Class it will return the object itself,
         * otherwise it will return the getClass() result of it.
         */
        private static Class getClass(Object x) {
            if (x instanceof Class) return (Class) x;
            return x.getClass();
        }
        
        /**
         * Gives the meta class to an Object.
         */
        private static MetaClass getMetaClass(Object receiver) {
            if (receiver == null) {
                return NullObject.getNullObject().getMetaClass();
            } else if (receiver instanceof GroovyObject) {
                return ((GroovyObject) receiver).getMetaClass(); 
            } else {
                return ((MetaClassRegistryImpl) GroovySystem.getMetaClassRegistry()).getMetaClass(receiver);
            }
        }
        
        private static class CallInfo {
            public Object[] args;
            public MetaMethod method;
            public MethodType targetType;
            public String name;
            public MethodHandle handle;
            public boolean useMetaClass = false;
            public MutableCallSite callSite;
            public Class sender;
            public boolean isVargs;
            public boolean safeNavigation, safeNavigationOrig;
            public boolean thisCall;
            public Class selector;
        }
        
        /**
         * Returns if a method is static
         */
        private static boolean isStatic(Method m) {
            int mods = m.getModifiers();
            return (mods & Modifier.STATIC) != 0;
        }
        
        /**
         * Creates a MethodHandle using a before selected MetaMethod.
         * If the MetaMethod has reflective information available, then
         * we will use that information to create the target MethodHandle. 
         * If that is not the case we will produce a handle, which will use the
         * MetaMethod itself for invocation.
         */
        private static void setHandleForMetaMethod(CallInfo info) {
            MetaMethod metaMethod = info.method;
            boolean isCategoryTypeMethod = metaMethod instanceof NewInstanceMetaMethod;
            
            if (metaMethod instanceof ReflectionMetaMethod) {
                ReflectionMetaMethod rmm = (ReflectionMetaMethod) metaMethod;
                metaMethod = rmm.getCachedMethod();
            }
            
            if (metaMethod instanceof CachedMethod) {
                CachedMethod cm = (CachedMethod) metaMethod;
                info.isVargs = cm.isVargsMethod();
                try {
                    Method m = cm.getCachedMethod();
                    info.handle = LOOKUP.unreflect(m);
                    if (!isCategoryTypeMethod && isStatic(m)) {
                        info.handle = MethodHandles.dropArguments(info.handle, 0, Class.class);
                    }
                } catch (IllegalAccessException e) {
                    throw new GroovyBugError(e);
                }
            } else if (info.method != null) {
                // dgm method helper path
                info.handle = META_METHOD_INVOKER;
                info.handle = info.handle.bindTo(info.method);
                if (info.method.getNativeParameterTypes().length==1 && 
                    info.args.length==1) 
                {
                    // the method expects a parameter but we don't provide an 
                    // argument for that. So we give in a Object[], containing 
                    // a null value
                    // since MethodHandles.insertArguments is a vargs method giving
                    // only the array would be like just giving a null value, so
                    // we need to wrap the array that represents our argument in
                    // another one for the vargs call
                    info.handle = MethodHandles.insertArguments(info.handle, 1, new Object[]{new Object[]{null}});
                } else if (info.method.isVargsMethod()) {
                    // the method expects the arguments as Object[] in a Object[]
                    info.handle = info.handle.asCollector(Object[].class, 1);
                    info.handle = info.handle.asCollector(Object[].class, info.targetType.parameterCount()-1);
                } else {
                    info.handle = info.handle.asCollector(Object[].class, info.targetType.parameterCount()-1);
                }
            }
        }
        
        /**
         * Uses the meta class to get a meta method.
         * There will be no meta method selected, if the meta class is no MetaClassImpl
         * or the meta class is an AdaptingMetaClass.
         */
        private static void chooseMethod(MetaClass mc, CallInfo ci) {
            if (!(mc instanceof MetaClassImpl) || mc instanceof AdaptingMetaClass) {return;}
            
            MetaClassImpl mci = (MetaClassImpl) mc;
            Object receiver = ci.args[0];
            if (receiver==null) {
                receiver = NullObject.getNullObject();
            } 
            
            if (receiver instanceof Class) {
                ci.method = mci.retrieveStaticMethod(ci.name, removeRealReceiver(ci.args));
            } else {
                ci.method = mci.getMethodWithCaching(ci.selector, ci.name, removeRealReceiver(ci.args), false);
            }
        }
        
        /**
         * Creates a MethodHandle, which will use the meta class path.
         * This method is called only if no handle has been created before. This
         * is usually the case if the method selection failed.
         */
        private static void setMetaClassCallHandleIfNedded(MetaClass mc, CallInfo ci) {
            if (ci.handle!=null) return;
            try {
                ci.useMetaClass = true;
                Object receiver = ci.args[0];
                if (receiver instanceof Class) {
                    ci.handle = LOOKUP.findVirtual(mc.getClass(), "invokeStaticMethod", MethodType.methodType(Object.class, Object.class, String.class, Object[].class));
                    ci.handle = ci.handle.bindTo(mc);
                } else {
                    boolean useShortForm = mc instanceof AdaptingMetaClass;
                    if (useShortForm) {
                        ci.handle = LOOKUP.findVirtual(MetaObjectProtocol.class, "invokeMethod", MethodType.methodType(Object.class, Object.class, String.class, Object[].class));
                    } else {
                        ci.handle = LOOKUP.findVirtual(MetaClass.class, "invokeMethod", INVOKE_METHOD_SIGNATURE);
                        ci.handle = MethodHandles.insertArguments(ci.handle, ci.handle.type().parameterCount()-2, false, true);
                    }
                    
                    ci.handle = ci.handle.bindTo(mc);
                    if (!useShortForm) {
                        ci.handle = ci.handle.bindTo(ci.selector);
                    }
                    
                    if (receiver instanceof GroovyObject) {
                        // if the meta class call fails we may still want to fall back to call
                        // GroovyObject#invokeMethod if the receiver is a GroovyObject
                        ci.handle = MethodHandles.catchException(ci.handle, MissingMethodException.class, GROOVY_OBJECT_INVOKER);
                    }
                }
                ci.handle = MethodHandles.insertArguments(ci.handle, 1, ci.name);
                ci.handle = ci.handle.asCollector(Object[].class, ci.targetType.parameterCount()-1);
            } catch (Exception e) {
                throw new GroovyBugError(e);
            }
            
           
        }

        /**
         * {@link GroovyObject#invokeMethod(String, Object)} path as fallback.
         * This method is called by the handle as exception handler in case the
         * selected method causes a MissingMethodExecutionFailed, where
         * we will just give through the exception, and a normal 
         * MissingMethodException where we call {@link GroovyObject#invokeMethod(String, Object)}
         * if receiver class, the type transported by the exception and the name
         * for the method stored in the exception and our current method name 
         * are equal.
         * Should those conditions not apply we just rethrow the exception.
         */
        public static Object invokeGroovyObjectInvoker(MissingMethodException e, Object receiver, String name, Object[] args) {
            if (e instanceof MissingMethodExecutionFailed) {
                throw (MissingMethodException)e.getCause();
            } else if (receiver.getClass() == e.getType() && e.getMethod().equals(name)) {
                //TODO: we should consider calling this one directly for MetaClassImpl,
                //      then we save the new method selection
                
                // in case there's nothing else, invoke the object's own invokeMethod()
                return ((GroovyObject)receiver).invokeMethod(name, args);
            } else {
                throw e;
            }
        }
        
        /**
         * Unwraps a {@link GroovyRuntimeException}.
         * This method is called by the handle to unwrap internal exceptions 
         * of the runtime.
         */
        public static Object unwrap(GroovyRuntimeException gre) throws Throwable {
            throw ScriptBytecodeAdapter.unwrap(gre);
        }
        
        /**
         * called by handle
         */
        public static boolean isSameMetaClass(MetaClass mc, Object receiver) {
            //TODO: remove this method if possible by switchpoint usage
            return receiver instanceof GroovyObject && mc==((GroovyObject)receiver).getMetaClass(); 
        }
        
        /**
         * Unwraps a {@link Wrapper}.
         * This method is called by the handle to unwrap a Wrapper, which
         * we use to force method selection.
         */
        public static Object unwrap(Object o) {
            Wrapper w = (Wrapper) o;
            return w.unwrap();
        }
        
        /**
         * Converts an Object to String.
         * This method is called by the handle to convert for example 
         * a GString to String.
         */
        public static String coerceToString(Object o) {
            return o.toString();
        }
        
        /**
         * Converts a Number to Byte.
         * This method is called by the handle to convert
         * Numbers to Byte.
         */
        public static Object coerceToByte(Object o) {
            return new Byte(((Number) o).byteValue());
        }
        
        /**
         * Converts an Object to BigInteger.
         * This method is called by the handle to convert
         * Numbers to BigInteger using {@link String#valueOf(Object)} 
         */
        public static Object coerceToBigInt(Object o) {
            return new BigInteger(String.valueOf((Number) o));
        }
        
        /**
         * Guard to check if the argument is null.
         * This method is called by the handle to check
         * if the provided argument is null.
         */
        public static boolean isNull(Object o) {
            return o == null;
        }
        
        /**
         * Guard to check if the argument is not null.
         * This method is called by the handle to check
         * if the provided argument is not null.
         */
        public static boolean isNotNull(Object o) {
            return o != null;
        }
        
        /**
         * Guard to check if the provided Object has the same
         * class as the provided Class. This method will
         * return false if the Object is null.
         */
        public static boolean sameClass(Class c, Object o) {
            if (o==null) return false;
            return o.getClass() == c;
        }
        
        /**
         * Corrects method argument wrapping.
         * In cases in which we want to force a certain method selection
         * we use Wrapper classes to transport the static type information.
         * This method will be used to undo thee wrapping.
         */
        private static void correctWrapping(CallInfo ci) {
            if (ci.useMetaClass) return;
            Class[] pt = ci.handle.type().parameterArray();
            for (int i=1; i<ci.args.length; i++) {
                if (ci.args[i] instanceof Wrapper) {
                    Class type = pt[i];
                    MethodType mt = MethodType.methodType(type, Object.class);
                    ci.handle = MethodHandles.filterArguments(ci.handle, i, UNWRAP_METHOD.asType(mt));
                }
            }
        }
        
        /**
         * There are some conversions we have to do explicitly.
         * These are GString to String, Number to Byte and Number to BigInteger
         * conversions.
         */
        private static void correctCoerce(CallInfo ci) {
            if (ci.useMetaClass) return;
            Class[] parameters = ci.handle.type().parameterArray();
            if (ci.args.length != parameters.length) {
                throw new GroovyBugError("at this point argument array length and parameter array length should be the same");
            }
            for (int i=1; i<ci.args.length; i++) {
                Object arg = ci.args[i];
                if (arg==null) continue;
                Class got = arg.getClass(); 
                if (arg instanceof GString && parameters[i] == String.class) {
                    ci.handle = MethodHandles.filterArguments(ci.handle, i, TO_STRING);                    
                } else if (parameters[i] == Byte.class && got != Byte.class) {
                    ci.handle = MethodHandles.filterArguments(ci.handle, i, TO_BYTE);
                } else if (parameters[i] == BigInteger.class && got != BigInteger.class) {
                    ci.handle = MethodHandles.filterArguments(ci.handle, i, TO_BIGINT);
                }
            }
        }
        
        /**
         * Gives a replacement receiver for null.
         * In case of the receiver being null we want to do the method
         * invocation on NullObject instead.
         */
        private static void correctNullReceiver(CallInfo ci){
            if (ci.args[0]!=null) return;
            ci.handle = ci.handle.bindTo(NullObject.getNullObject());
            ci.handle = MethodHandles.dropArguments(ci.handle, 0, ci.targetType.parameterType(0));
        }
        
        /**
         * Sets all argument and receiver guards.
         */
        private static void setGuards(CallInfo ci, Object receiver) {
            if (ci.handle==null) return;
            
            MethodHandle fallback = makeMethodFallBack(ci.callSite, ci.sender, ci.name, ci.targetType, ci.safeNavigationOrig, ci.thisCall);
            
            // special guards for receiver
            if (receiver instanceof GroovyObject) {
                GroovyObject go = (GroovyObject) receiver;
                MetaClass mc = (MetaClass) go.getMetaClass();
                MethodHandle test = SAME_MC.bindTo(mc); 
                // drop dummy receiver
                test = test.asType(MethodType.methodType(boolean.class,ci.targetType.parameterType(0)));
                ci.handle = MethodHandles.guardWithTest(test, ci.handle, fallback);
            } else if (receiver != null) {
                // handle constant meta class
                ci.handle = switchPoint.guardWithTest(ci.handle, fallback);
                
            }
            
            // guards for receiver and parameter
            Class[] pt = ci.handle.type().parameterArray();
            for (int i=0; i<ci.args.length; i++) {
                Object arg = ci.args[i];
                MethodHandle test = null;
                if (arg==null) {
                    test = IS_NULL.asType(MethodType.methodType(boolean.class, pt[i]));
                } else {
                    Class argClass = arg.getClass();
                    if (Modifier.isFinal(argClass.getModifiers()) && argumentClassIsParameterClass(argClass,pt[i])) continue;
                    test = SAME_CLASS.
                                bindTo(argClass).
                                asType(MethodType.methodType(boolean.class, pt[i]));
                }
                Class[] drops = new Class[i];
                for (int j=0; j<drops.length; j++) drops[j] = pt[j];
                test = MethodHandles.dropArguments(test, 0, drops);
                ci.handle = MethodHandles.guardWithTest(test, ci.handle, fallback);
            }
        }
        
        /**
         * Get wrapper class for a given class. 
         * If the class is for a primitive number type, then the wrapper class
         * will be returned. If it is no primtive number type, we return the 
         * class itself.
         */
        private static Class getWrapperClass(Class c) {
            if (c == Integer.TYPE) {
                c = Integer.class;
            } else if (c == Byte.TYPE) {
                c = Byte.class;
            } else if (c == Long.TYPE) {
                c = Long.class;
            } else if (c == Double.TYPE) {
                c = Double.class;
            } else if (c == Float.TYPE) {
                c = Float.class;
            }
            return c;
        }
        
        /**
         * Realizes an unsharp equal for the class. 
         * In general we return true if the provided arguments are the same. But
         * we will also return true if our argument class is a wrapper for
         * the parameter class. For example the parameter is an int and the
         * argument class is a wrapper.
         */
        private static boolean argumentClassIsParameterClass(Class argumentClass, Class parameterClass) {
            if (argumentClass == parameterClass) return true;
            if (getWrapperClass(parameterClass) == argumentClass) return true;
            return false;
        }
        
        /**
         * Handles cases in which we have to correct the length of arguments
         * using the parameters. This might be needed for vargs and for one 
         * parameter calls without arguments (null is used then).  
         */
        private static void correctParameterLenth(CallInfo info) {
            Class[] params = info.handle.type().parameterArray();
            
            if (info.handle==null) return;
            if (!info.isVargs) {
                if (params.length != info.args.length) {
                  //TODO: add null argument
                }
                return;
            }

            Class lastParam = params[params.length-1];
            Object lastArg = info.args[info.args.length-1];
            if (params.length == info.args.length) {
                // may need rewrap
                if (lastParam == lastArg || lastArg == null) return;
                if (lastParam.isInstance(lastArg)) return;
                // arg is not null and not assignment compatible
                // so we really need to rewrap
                info.handle = info.handle.asCollector(lastParam, 1);
            } else if (params.length > info.args.length) {
                // we depend on the method selection having done a good 
                // job before already, so the only case for this here is, that
                // we have no argument for the array, meaning params.length is
                // args.length+1. In that case we have to fill in an empty array
                info.handle = MethodHandles.insertArguments(info.handle, params.length-1, Array.newInstance(lastParam.getComponentType(), 0));
            } else { //params.length < args.length
                // we depend on the method selection having done a good 
                // job before already, so the only case for this here is, that
                // all trailing arguments belong into the vargs array
                info.handle = info.handle.asCollector(
                        lastParam,
                        info.args.length - params.length + 1);
            }
            
        }
        
        /**
         * Adds the standard exception handler.  
         */
        private static void addExceptionHandler(CallInfo info) {
            //TODO: if we would know exactly which paths require the exceptions
            //      and which paths not, we can sometimes save this guard 
            if (info.handle==null) return;
            Class returnType = info.handle.type().returnType();
            if (returnType!=Object.class) {
                MethodType mtype = MethodType.methodType(returnType, GroovyRuntimeException.class); 
                info.handle = MethodHandles.catchException(info.handle, GroovyRuntimeException.class, UNWRAP_EXCEPTION.asType(mtype));
            } else {
                info.handle = MethodHandles.catchException(info.handle, GroovyRuntimeException.class, UNWRAP_EXCEPTION);
            }
        }
        
        /**
         * Sets the null constant for safe navigation.
         * In case of foo?.bar() and foo being null, we don't call the method,
         * instead we simply return null. This produces a handle, which will 
         * return the constant.
         */
        private static boolean setNullForSafeNavigation(CallInfo info) {
            if (!info.safeNavigation) return false;
            info.handle = MethodHandles.dropArguments(NULL_REF,0,info.targetType.parameterArray());
            return true;
        }
        
        /**
         * Sets the method selection base.
         */
        private static void setSelector(CallInfo ci, MetaClass mc) {
            if (ci.thisCall) {
                ci.selector = ci.sender;
            } else if (ci.args[0]==null) {
                ci.selector = NullObject.class;
            } else {
                ci.selector = mc.getTheClass();
            }
        }

        public static Object selectProperty(MutableCallSite callSite, Class sender, String propertyName, int callType, Object receiver) throws Throwable {
            boolean safeNavigation = (callType & 4)!=0;
            boolean groovyObject = (callType & 2)!=0;

            CallInfo callInfo = new CallInfo();
            callInfo.targetType = callSite.type();
            callInfo.name = propertyName;
            callInfo.args = new Object[]{receiver};
            callInfo.callSite = callSite;
            callInfo.sender = sender;
            callInfo.safeNavigationOrig = safeNavigation;
            callInfo.safeNavigation = safeNavigation && receiver==null;
            callInfo.thisCall = false;
            throw new GroovyBugError("NYI");
        }

        
        /**
         * Core method for indy method selection using runtime types.
         */
        public static Object selectMethod(MutableCallSite callSite, Class sender, String methodName, Boolean safeNavigation, Boolean thisCall, Object dummyReceiver, Object[] arguments) throws Throwable {
            //TODO: handle GroovyInterceptable 
            CallInfo callInfo = new CallInfo();
            callInfo.targetType = callSite.type();
            callInfo.name = methodName;
            callInfo.args = arguments;
            callInfo.callSite = callSite;
            callInfo.sender = sender;
            callInfo.safeNavigationOrig = safeNavigation;
            callInfo.safeNavigation = safeNavigation && arguments[0]==null;
            callInfo.thisCall = thisCall;

            if (!setNullForSafeNavigation(callInfo)) {
                //            setInterceptableHandle(callInfo);
                MetaClass mc = getMetaClass(callInfo.args[0]);
                setSelector(callInfo, mc);
                chooseMethod(mc, callInfo);
                setHandleForMetaMethod(callInfo);
                setMetaClassCallHandleIfNedded(mc, callInfo);
                correctWrapping(callInfo);
                correctParameterLenth(callInfo);
                correctCoerce(callInfo);
                correctNullReceiver(callInfo);
                callInfo.handle =  MethodHandles.explicitCastArguments(callInfo.handle,callInfo.targetType);

                addExceptionHandler(callInfo);
            } 
            setGuards(callInfo, callInfo.args[0]);
            callSite.setTarget(callInfo.handle);
            
            MethodHandle call = callInfo.handle.asSpreader(Object[].class, callInfo.args.length);
            call = call.asType(MethodType.methodType(Object.class,Object[].class));
            return call.invokeExact(callInfo.args);
        }
        
        /**
         * Helper method to remove the receiver from the argument array
         * by producing a new array.
         */
        private static Object[] removeRealReceiver(Object[] args) {
            Object[] ar = new Object[args.length-1];
            for (int i=1; i<args.length; i++) {
                ar[i-1] = args[i];
            }
            return ar;
        }
}
