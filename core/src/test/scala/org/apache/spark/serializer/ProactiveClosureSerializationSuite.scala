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

package org.apache.spark.serializer;

import java.io.NotSerializableException

import org.scalatest.FunSuite

import org.apache.spark.SparkException
import org.apache.spark.SharedSparkContext

class UnserializableClass {
    def op[T](x: T) = x.toString
}

class SerializableClass extends UnserializableClass with Serializable { }

class ProactiveClosureSerializationSuite extends FunSuite with SharedSparkContext {

    test("throws expected serialization exceptions on actions") {
        val data = sc.parallelize(0 until 1000)
        val uc = new UnserializableClass
    
        val ex = intercept[SparkException] {
            data.map(uc.op(_)).count
        }
        
        assert(ex.getCause.isInstanceOf[NotSerializableException])
    }

    test("throws proactive serialization exceptions on transformations") {
        val data = sc.parallelize(0 until 1000)
        val uc = new UnserializableClass
    
        val ex = intercept[SparkException] {
            data.map(uc.op(_))
        }
        
        assert(ex.getCause.isInstanceOf[NotSerializableException])
    }
}
