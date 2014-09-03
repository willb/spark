/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.util

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import scala.collection.mutable.Map
import scala.collection.mutable.Set

import scala.reflect.ClassTag

import com.esotericsoftware.kryo.{Kryo, KryoException}
import com.esotericsoftware.reflectasm.shaded.org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Type}
import com.esotericsoftware.reflectasm.shaded.org.objectweb.asm.Opcodes._

import org.apache.spark.{Logging, SparkEnv, SparkException, SparkContext, ContextCleaner}
import org.apache.spark.serializer.{SerializerInstance, KryoSerializer}

private[spark] sealed trait CleanedClosure {}

private[spark] sealed abstract class BoxedClosure[T <: Any : ClassTag](f: T) {
  def get: T = f
}

private[spark] case class CleanedClosure1[T <: Any : ClassTag,
                                          U <: Any : ClassTag](f: T => U) 
    extends BoxedClosure[T => U](f) 
    with CleanedClosure 
    with Function1[T, U] {
  def apply(v:T): U = f.apply(v)
}

private[spark] case class CleanedClosure2[T1 <: Any : ClassTag,
                                          T2 <: Any : ClassTag,
                                          U <: Any : ClassTag](f: (T1,T2) => U) 
    extends BoxedClosure[(T1, T2) => U](f) 
    with CleanedClosure 
    with Function2[T1, T2, U] {
  def apply(v1: T1, v2: T2): U = f.apply(v1, v2)
}

private[spark] case class CleanedClosure3[T1 <: Any : ClassTag,
                                          T2 <: Any : ClassTag,
                                          T3 <: Any : ClassTag,
                                          U <: Any : ClassTag](f: (T1,T2,T3) => U) 
    extends BoxedClosure[(T1, T2, T3) => U](f) 
    with CleanedClosure 
    with Function3[T1, T2, T3, U] {
  def apply(v1: T1, v2: T2, v3: T3): U = f.apply(v1, v2, v3)
}

private[spark] case class CleanedClosure4[T1 <: Any : ClassTag,
                                          T2 <: Any : ClassTag,
                                          T3 <: Any : ClassTag,
                                          T4 <: Any : ClassTag,
                                          U <: Any : ClassTag](f: (T1,T2,T3,T4) => U) 
    extends BoxedClosure[(T1, T2, T3, T4) => U](f) 
    with CleanedClosure 
    with Function4[T1, T2, T3, T4, U] {
  def apply(v1: T1, v2: T2, v3: T3, v4: T4): U = f.apply(v1, v2, v3, v4)
}

private[spark] object BoxedClosure {
  def make[T <: Any : ClassTag,
           U <: Any : ClassTag](f: T => U): T => U =
    CleanedClosure1[T,U](f).asInstanceOf[Function1[T,U]]

  def make[T1 <: Any : ClassTag,
           T2 <: Any : ClassTag,
           U <: Any : ClassTag](f: (T1, T2) => U): (T1, T2) => U =
    CleanedClosure2[T1,T2,U](f).asInstanceOf[Function2[T1,T2,U]]

  def make[T1 <: Any : ClassTag,
           T2 <: Any : ClassTag,
           T3 <: Any : ClassTag,
           U <: Any : ClassTag](f: (T1, T2, T3) => U): (T1, T2, T3) => U =
    CleanedClosure3[T1,T2,T3,U](f).asInstanceOf[Function3[T1,T2,T3,U]]

  def make[T1 <: Any : ClassTag,
           T2 <: Any : ClassTag,
           T3 <: Any : ClassTag,
           T4 <: Any : ClassTag,
           U <: Any : ClassTag](f: (T1, T2, T3, T4) => U): (T1, T2, T3, T4) => U =
     CleanedClosure4[T1,T2,T3,T4,U](f).asInstanceOf[Function4[T1,T2,T3,T4,U]]

  def make[T <: Any](f: T): T = f

  def boxable(f: Any): Boolean = {
    f match {
      case _:Function1[_,_] => true
      case _:Function2[_,_,_] => true
      case _:Function3[_,_,_,_] => true
      case _:Function4[_,_,_,_,_] => true
      case _ => false
    }
  }
}

private[spark] object ClosureCleaner extends Logging {
  private val kryoHandle = new ThreadLocal[Kryo]()
  
  private def getKryo(sc: SparkContext) = {
    if(kryoHandle.get == null) {
      kryoHandle.set(new KryoSerializer(sc.getConf).newKryo())
    }
    
    kryoHandle.get
  }
  
  // Get an ASM class reader for a given class from the JAR that loaded it
  private def getClassReader(cls: Class[_]): ClassReader = {
    // Copy data over, before delegating to ClassReader - else we can run out of open file handles.
    val className = cls.getName.replaceFirst("^.*\\.", "") + ".class"
    val resourceStream = cls.getResourceAsStream(className)
    // todo: Fixme - continuing with earlier behavior ...
    if (resourceStream == null) return new ClassReader(resourceStream)

    val baos = new ByteArrayOutputStream(128)
    Utils.copyStream(resourceStream, baos, true)
    new ClassReader(new ByteArrayInputStream(baos.toByteArray))
  }

  // Check whether a class represents a Scala closure
  private def isClosure(cls: Class[_]): Boolean = {
    cls.getName.contains("$anonfun$")
  }

  // Get a list of the classes of the outer objects of a given closure object, obj;
  // the outer objects are defined as any closures that obj is nested within, plus
  // possibly the class that the outermost closure is in, if any. We stop searching
  // for outer objects beyond that because cloning the user's object is probably
  // not a good idea (whereas we can clone closure objects just fine since we
  // understand how all their fields are used).
  private def getOuterClasses(obj: AnyRef): List[Class[_]] = {
    for (f <- obj.getClass.getDeclaredFields if f.getName == "$outer") {
      f.setAccessible(true)
      if (isClosure(f.getType)) {
        return f.getType :: getOuterClasses(f.get(obj))
      } else {
        return f.getType :: Nil // Stop at the first $outer that is not a closure
      }
    }
    Nil
  }

  // Get a list of the outer objects for a given closure object.
  private def getOuterObjects(obj: AnyRef): List[AnyRef] = {
    for (f <- obj.getClass.getDeclaredFields if f.getName == "$outer") {
      f.setAccessible(true)
      if (isClosure(f.getType)) {
        return f.get(obj) :: getOuterObjects(f.get(obj))
      } else {
        return f.get(obj) :: Nil // Stop at the first $outer that is not a closure
      }
    }
    Nil
  }

  private def getInnerClasses(obj: AnyRef): List[Class[_]] = {
    val seen = Set[Class[_]](obj.getClass)
    var stack = List[Class[_]](obj.getClass)
    while (!stack.isEmpty) {
      val cr = getClassReader(stack.head)
      stack = stack.tail
      val set = Set[Class[_]]()
      cr.accept(new InnerClosureFinder(set), 0)
      for (cls <- set -- seen) {
        seen += cls
        stack = cls :: stack
      }
    }
    return (seen - obj.getClass).toList
  }

  private def createNullValue(cls: Class[_]): AnyRef = {
    if (cls.isPrimitive) {
      new java.lang.Byte(0: Byte) // Should be convertible to any primitive type
    } else {
      null
    }
  }
  
  def clean[F <: AnyRef : ClassTag](func: F, captureNow: Boolean, sc: SparkContext): F = {
    if (func.isInstanceOf[CleanedClosure]) func else actuallyClean(func, captureNow, sc)
  }
  
  def actuallyClean[F <: AnyRef : ClassTag](func: F, 
      captureNow: Boolean, 
      sc: SparkContext): F = {
    // TODO: cache outerClasses / innerClasses / accessedFields
    val outerClasses = getOuterClasses(func)
    val innerClasses = getInnerClasses(func)
    val outerObjects = getOuterObjects(func)

    val accessedFields = Map[Class[_], Set[String]]()

    val kryo = getKryo(sc)
    
    getClassReader(func.getClass).accept(new ReturnStatementFinder(), 0)
    
    for (cls <- outerClasses)
      accessedFields(cls) = Set[String]()
    for (cls <- func.getClass :: innerClasses)
      getClassReader(cls).accept(new FieldAccessFinder(accessedFields), 0)
    // logInfo("accessedFields: " + accessedFields)

    val inInterpreter = {
      try {
        val interpClass = Class.forName("spark.repl.Main")
        interpClass.getMethod("interp").invoke(null) != null
      } catch {
        case _: ClassNotFoundException => true
      }
    }

    var outerPairs: List[(Class[_], AnyRef)] = (outerClasses zip outerObjects).reverse
    var outer: AnyRef = null
    if (outerPairs.size > 0 && !isClosure(outerPairs.head._1)) {
      // The closure is ultimately nested inside a class; keep the object of that
      // class without cloning it since we don't want to clone the user's objects.
      outer = outerPairs.head._2
      outerPairs = outerPairs.tail
    }
    // Clone the closure objects themselves, nulling out any fields that are not
    // used in the closure we're working on or any of its inner closures.
    for ((cls, obj) <- outerPairs) {
      outer = instantiateClass(cls, outer, inInterpreter)
      for (fieldName <- accessedFields(cls)) {
        val field = cls.getDeclaredField(fieldName)
        field.setAccessible(true)
        val value = field.get(obj)
        // logInfo("1: Setting " + fieldName + " on " + cls + " to " + value);
        field.set(outer, value)
      }
    }

    if (outer != null) {
      // logInfo("2: Setting $outer on " + func.getClass + " to " + outer);
      val field = func.getClass.getDeclaredField("$outer")
      field.setAccessible(true)
      field.set(func, kryo.copyShallow(outer))
    }
    
    if (captureNow && BoxedClosure.boxable(func)) {
      ContextCleaner.withCurrentCleaner(sc.cleaner){
        BoxedClosure.make(kryo.copyShallow(func))
      }
    } else {
      func
    }
  }

  // private def cloneViaSerializing[T: ClassTag](func: T): T = {
  //   try {
  //     val bb = serializer.serialize[T](func)
  //     logWarning("serializing a func with size " + bb.array().length)
  //     serializer.deserialize[T](bb)
  //   } catch {
  //     case ex: Exception => throw new SparkException("Task not serializable", ex)
  //   }
  // }

  private def instantiateClass(cls: Class[_], outer: AnyRef, inInterpreter: Boolean): AnyRef = {
    logWarning("Creating a " + cls + " with outer = " + outer)
    if (!inInterpreter) {
      // This is a bona fide closure class, whose constructor has no effects
      // other than to set its fields, so use its constructor
      val cons = cls.getConstructors()(0)
      val params = cons.getParameterTypes.map(createNullValue).toArray
      if (outer != null) {
        params(0) = outer // First param is always outer object
      }
      return cons.newInstance(params: _*).asInstanceOf[AnyRef]
    } else {
      // Use reflection to instantiate object without calling constructor
      val rf = sun.reflect.ReflectionFactory.getReflectionFactory()
      val parentCtor = classOf[java.lang.Object].getDeclaredConstructor()
      val newCtor = rf.newConstructorForSerialization(cls, parentCtor)
      val obj = newCtor.newInstance().asInstanceOf[AnyRef]
      if (outer != null) {
        // logInfo("3: Setting $outer on " + cls + " to " + outer);
        val field = cls.getDeclaredField("$outer")
        field.setAccessible(true)
        field.set(obj, outer)
      }
      obj
    }
  }
}

private[spark]
class ReturnStatementFinder extends ClassVisitor(ASM4) {
  override def visitMethod(access: Int, name: String, desc: String,
      sig: String, exceptions: Array[String]): MethodVisitor = {
    if (name.contains("apply")) {
      new MethodVisitor(ASM4) {
        override def visitTypeInsn(op: Int, tp: String) {
          if (op == NEW && tp.contains("scala/runtime/NonLocalReturnControl")) {
            throw new SparkException("Return statements aren't allowed in Spark closures")
          }
        }
      }
    } else {
      new MethodVisitor(ASM4) {}
    }
  }
}

private[spark]
class FieldAccessFinder(output: Map[Class[_], Set[String]]) extends ClassVisitor(ASM4) {
  override def visitMethod(access: Int, name: String, desc: String,
      sig: String, exceptions: Array[String]): MethodVisitor = {
    new MethodVisitor(ASM4) {
      override def visitFieldInsn(op: Int, owner: String, name: String, desc: String) {
        if (op == GETFIELD) {
          for (cl <- output.keys if cl.getName == owner.replace('/', '.')) {
            output(cl) += name
          }
        }
      }

      override def visitMethodInsn(op: Int, owner: String, name: String,
          desc: String) {
        // Check for calls a getter method for a variable in an interpreter wrapper object.
        // This means that the corresponding field will be accessed, so we should save it.
        if (op == INVOKEVIRTUAL && owner.endsWith("$iwC") && !name.endsWith("$outer")) {
          for (cl <- output.keys if cl.getName == owner.replace('/', '.')) {
            output(cl) += name
          }
        }
      }
    }
  }
}

private[spark] class InnerClosureFinder(output: Set[Class[_]]) extends ClassVisitor(ASM4) {
  var myName: String = null

  override def visit(version: Int, access: Int, name: String, sig: String,
      superName: String, interfaces: Array[String]) {
    myName = name
  }

  override def visitMethod(access: Int, name: String, desc: String,
      sig: String, exceptions: Array[String]): MethodVisitor = {
    new MethodVisitor(ASM4) {
      override def visitMethodInsn(op: Int, owner: String, name: String,
          desc: String) {
        val argTypes = Type.getArgumentTypes(desc)
        if (op == INVOKESPECIAL && name == "<init>" && argTypes.length > 0
            && argTypes(0).toString.startsWith("L") // is it an object?
            && argTypes(0).getInternalName == myName) {
          output += Class.forName(
              owner.replace('/', '.'),
              false,
              Thread.currentThread.getContextClassLoader)
        }
      }
    }
  }
}
