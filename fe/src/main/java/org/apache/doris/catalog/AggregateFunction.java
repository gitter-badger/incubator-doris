// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.catalog;

import static org.apache.doris.common.io.IOUtils.readOptionStringOrNull;
import static org.apache.doris.common.io.IOUtils.writeOptionString;

import org.apache.doris.common.io.IOUtils;

import org.apache.doris.analysis.FunctionName;
import org.apache.doris.analysis.HdfsURI;
import org.apache.doris.thrift.TAggregateFunction;
import org.apache.doris.thrift.TFunction;
import org.apache.doris.thrift.TFunctionBinaryType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// import org.apache.doris.analysis.String;

/**
 * Internal representation of an aggregate function.
 * TODO: Create separate AnalyticFunction class
 */
public class AggregateFunction extends Function {
    
    private static final Logger LOG = LogManager.getLogger(AggregateFunction.class);
    // Set if different from retType_, null otherwise.
    private Type intermediateType;

    // The symbol inside the binary at location_ that contains this particular.
    // They can be null if it is not required.
    private String updateFnSymbol;
    private String initFnSymbol;
    private String serializeFnSymbol;
    private String mergeFnSymbol;
    private String getValueFnSymbol;
    private String removeFnSymbol;
    private String finalizeFnSymbol;

    private static String BE_BUILTINS_CLASS = "AggregateFunctions";

    // If true, this aggregate function should ignore distinct.
    // e.g. min(distinct col) == min(col).
    // TODO: currently it is not possible for user functions to specify this. We should
    // extend the create aggregate function stmt to allow additional metadata like this.
    private boolean ignoresDistinct;

    // True if this function can appear within an analytic expr (fn() OVER(...)).
    // TODO: Instead of manually setting this flag for all builtin aggregate functions
    // we should identify this property from the function itself (e.g., based on which
    // functions of the UDA API are implemented).
    // Currently, there is no reliable way of doing that.
    private boolean isAnalyticFn;

    // True if this function can be used for aggregation (without an OVER() clause).
    private boolean isAggregateFn;

    // True if this function returns a non-null value on an empty input. It is used
    // primarily during the rewrite of scalar subqueries.
    // TODO: Instead of manually setting this flag, we should identify this
    // property from the function itself (e.g. evaluating the function on an
    // empty input in BE).
    private boolean returnsNonNullOnEmpty;

    // only used for serialization
    protected AggregateFunction() {
    }

    public AggregateFunction(FunctionName fnName, ArrayList<Type> argTypes, Type retType,
            boolean hasVarArgs) {
        super(fnName, argTypes, retType, hasVarArgs);
    }

    public AggregateFunction(FunctionName fnName, List<Type> argTypes,
            Type retType, Type intermediateType,
            HdfsURI location, String updateFnSymbol, String initFnSymbol,
            String serializeFnSymbol, String mergeFnSymbol, String getValueFnSymbol,
            String removeFnSymbol, String finalizeFnSymbol) {
        super(fnName, argTypes, retType, false);
        setLocation(location);
        this.intermediateType = (intermediateType.equals(retType)) ? null : intermediateType;
        this.updateFnSymbol = updateFnSymbol;
        this.initFnSymbol = initFnSymbol;
        this.serializeFnSymbol = serializeFnSymbol;
        this.mergeFnSymbol = mergeFnSymbol;
        this.getValueFnSymbol = getValueFnSymbol;
        this.removeFnSymbol = removeFnSymbol;
        this.finalizeFnSymbol = finalizeFnSymbol;
        ignoresDistinct = false;
        isAnalyticFn = false;
        isAggregateFn = true;
        returnsNonNullOnEmpty = false;
    }

    public static AggregateFunction createBuiltin(String name,
            List<Type> argTypes, Type retType, Type intermediateType,
            String initFnSymbol, String updateFnSymbol, String mergeFnSymbol,
            String serializeFnSymbol, String finalizeFnSymbol, boolean ignoresDistinct,
            boolean isAnalyticFn, boolean returnsNonNullOnEmpty) {
        return createBuiltin(name, argTypes, retType, intermediateType, initFnSymbol,
            updateFnSymbol, mergeFnSymbol, serializeFnSymbol, null, null, finalizeFnSymbol,
            ignoresDistinct, isAnalyticFn, returnsNonNullOnEmpty);
    }

    public static AggregateFunction createBuiltin(String name,
            List<Type> argTypes, Type retType, Type intermediateType,
            String initFnSymbol, String updateFnSymbol, String mergeFnSymbol,
            String serializeFnSymbol, String getValueFnSymbol, String removeFnSymbol,
            String finalizeFnSymbol, boolean ignoresDistinct, boolean isAnalyticFn,
            boolean returnsNonNullOnEmpty) {
        AggregateFunction fn = new AggregateFunction(new FunctionName(name),
                argTypes, retType, intermediateType, null, updateFnSymbol, initFnSymbol,
                serializeFnSymbol, mergeFnSymbol, getValueFnSymbol, removeFnSymbol,
                finalizeFnSymbol);
        fn.setBinaryType(TFunctionBinaryType.BUILTIN);
        fn.ignoresDistinct = ignoresDistinct;
        fn.isAnalyticFn = isAnalyticFn;
        fn.isAggregateFn = true;
        fn.returnsNonNullOnEmpty = returnsNonNullOnEmpty;
        return fn;
    }

    public static AggregateFunction createAnalyticBuiltin(String name,
            List<Type> argTypes, Type retType, Type intermediateType) {
        return createAnalyticBuiltin(name, argTypes, retType, intermediateType, null,
                null, null, null, null, true);
    }

    public static AggregateFunction createAnalyticBuiltin(String name,
            List<Type> argTypes, Type retType, Type intermediateType,
            String initFnSymbol, String updateFnSymbol, String removeFnSymbol,
            String getValueFnSymbol, String finalizeFnSymbol) {
        return createAnalyticBuiltin(name, argTypes, retType, intermediateType,
                initFnSymbol, updateFnSymbol, removeFnSymbol, getValueFnSymbol, finalizeFnSymbol,
                true);
    }

    public static AggregateFunction createAnalyticBuiltin(String name,
            List<Type> argTypes, Type retType, Type intermediateType,
            String initFnSymbol, String updateFnSymbol, String removeFnSymbol,
            String getValueFnSymbol, String finalizeFnSymbol, boolean isUserVisible) {
        AggregateFunction fn = new AggregateFunction(new FunctionName(name),
                argTypes, retType, intermediateType, null, updateFnSymbol, initFnSymbol,
                null, null, getValueFnSymbol, removeFnSymbol, finalizeFnSymbol);
        fn.setBinaryType(TFunctionBinaryType.BUILTIN);
        fn.ignoresDistinct = false;
        fn.isAnalyticFn = true;
        fn.isAggregateFn = false;
        fn.returnsNonNullOnEmpty = false;
        fn.setUserVisible(isUserVisible);
        return fn;
    }

    public String getUpdateFnSymbol() { return updateFnSymbol; }
    public String getInitFnSymbol() { return initFnSymbol; }
    public String getSerializeFnSymbol() { return serializeFnSymbol; }
    public String getMergeFnSymbol() { return mergeFnSymbol; }
    public String getGetValueFnSymbol() { return getValueFnSymbol; }
    public String getRemoveFnSymbol() { return removeFnSymbol; }
    public String getFinalizeFnSymbol() { return finalizeFnSymbol; }
    public boolean ignoresDistinct() { return ignoresDistinct; }
    public boolean isAnalyticFn() { return isAnalyticFn; }
    public boolean isAggregateFn() { return isAggregateFn; }
    public boolean returnsNonNullOnEmpty() { return returnsNonNullOnEmpty; }

    /**
     * Returns the intermediate type of this aggregate function or null
     * if it is identical to the return type.
     */
    public Type getIntermediateType() { return intermediateType; }
    public void setUpdateFnSymbol(String fn) { updateFnSymbol = fn; }
    public void setInitFnSymbol(String fn) { initFnSymbol = fn; }
    public void setSerializeFnSymbol(String fn) { serializeFnSymbol = fn; }
    public void setMergeFnSymbol(String fn) { mergeFnSymbol = fn; }
    public void setGetValueFnSymbol(String fn) { getValueFnSymbol = fn; }
    public void setRemoveFnSymbol(String fn) { removeFnSymbol = fn; }
    public void setFinalizeFnSymbol(String fn) { finalizeFnSymbol = fn; }
    public void setIntermediateType(Type t) { intermediateType = t; }

    @Override
    public String toSql(boolean ifNotExists) {
        StringBuilder sb = new StringBuilder("CREATE AGGREGATE FUNCTION ");
        if (ifNotExists) {
            sb.append("IF NOT EXISTS ");
        }
        sb.append(dbName() + "." + signatureString() + "\n")
                .append(" RETURNS " + getReturnType() + "\n");
        if (getIntermediateType() != null) {
            sb.append(" INTERMEDIATE " + getIntermediateType() + "\n");
        }
        sb.append(" LOCATION '" + getLocation() + "'\n")
                .append(" UPDATE_FN='" + getUpdateFnSymbol() + "'\n")
                .append(" INIT_FN='" + getInitFnSymbol() + "'\n")
                .append(" MERGE_FN='" + getMergeFnSymbol() + "'\n");
        if (getSerializeFnSymbol() != null) {
            sb.append(" SERIALIZE_FN='" + getSerializeFnSymbol() + "'\n");
        }
        if (getFinalizeFnSymbol() != null) {
            sb.append(" FINALIZE_FN='" + getFinalizeFnSymbol() + "'\n");
        }
        return sb.toString();
    }

    @Override
    public TFunction toThrift() {
        TFunction fn = super.toThrift();
        TAggregateFunction aggFn = new TAggregateFunction();
        aggFn.setIs_analytic_only_fn(isAnalyticFn && !isAggregateFn);
        aggFn.setUpdate_fn_symbol(updateFnSymbol);
        aggFn.setInit_fn_symbol(initFnSymbol);
        if (serializeFnSymbol != null) {
            aggFn.setSerialize_fn_symbol(serializeFnSymbol);
        }
        aggFn.setMerge_fn_symbol(mergeFnSymbol);
        if (getValueFnSymbol  != null) {
            aggFn.setGet_value_fn_symbol(getValueFnSymbol);
        }
        if (removeFnSymbol  != null) {
            aggFn.setRemove_fn_symbol(removeFnSymbol);
        }
        if (finalizeFnSymbol  != null) {
            aggFn.setFinalize_fn_symbol(finalizeFnSymbol);
        }
        if (intermediateType != null) {
            aggFn.setIntermediate_type(intermediateType.toThrift());
        } else {
            aggFn.setIntermediate_type(getReturnType().toThrift());
        }
        //    agg_fn.setIgnores_distinct(ignoresDistinct);
        fn.setAggregate_fn(aggFn);
        return fn;
    }

    @Override
    public void write(DataOutput output) throws IOException {
        // 1. type
        FunctionType.AGGREGATE.write(output);
        // 2. parent
        super.write(output);
        // 3. self's member
        boolean hasInterType = intermediateType != null;
        output.writeBoolean(hasInterType);
        if (hasInterType) {
            ColumnType.write(output, intermediateType);
        }
        writeOptionString(output, updateFnSymbol);
        writeOptionString(output, initFnSymbol);
        writeOptionString(output, serializeFnSymbol);
        writeOptionString(output, mergeFnSymbol);
        writeOptionString(output, getValueFnSymbol);
        writeOptionString(output, removeFnSymbol);
        writeOptionString(output, finalizeFnSymbol);

        output.writeBoolean(ignoresDistinct);
        output.writeBoolean(isAnalyticFn);
        output.writeBoolean(isAggregateFn);
        output.writeBoolean(returnsNonNullOnEmpty);
    }

    @Override
    public void readFields(DataInput input) throws IOException {
        super.readFields(input);

        if (input.readBoolean()) {
            intermediateType = ColumnType.read(input);
        }
        updateFnSymbol = readOptionStringOrNull(input);
        initFnSymbol = readOptionStringOrNull(input);
        serializeFnSymbol = readOptionStringOrNull(input);
        mergeFnSymbol = readOptionStringOrNull(input);
        getValueFnSymbol = readOptionStringOrNull(input);
        removeFnSymbol = readOptionStringOrNull(input);
        finalizeFnSymbol = readOptionStringOrNull(input);
        ignoresDistinct = input.readBoolean();
        isAnalyticFn = input.readBoolean();
        isAggregateFn = input.readBoolean();
        returnsNonNullOnEmpty = input.readBoolean();
    }
}

