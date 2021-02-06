/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */


package org.elasticsearch.xpack.vectors.mapper;

import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.search.DocValuesFieldExistsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.Version;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.common.xcontent.XContentParser.Token;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.ParametrizedFieldMapper;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.SourceValueFetcher;
import org.elasticsearch.index.mapper.TextSearchInfo;
import org.elasticsearch.index.mapper.ValueFetcher;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.search.DocValueFormat;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;
import org.elasticsearch.search.lookup.SearchLookup;
import org.elasticsearch.xpack.vectors.query.VectorIndexFieldData;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.elasticsearch.common.xcontent.XContentParserUtils.ensureExpectedToken;

/**
 * A {@link FieldMapper} for indexing a sparse vector of floats.
 */
@Deprecated
public class SparseVectorFieldMapper extends ParametrizedFieldMapper {

    private static final DeprecationLogger deprecationLogger = DeprecationLogger.getLogger(SparseVectorFieldMapper.class);
    public static final String DEPRECATION_MESSAGE = "The [sparse_vector] field type is deprecated and will be removed in 8.0.";

    public static final String CONTENT_TYPE = "sparse_vector";
    public static short MAX_DIMS_COUNT = 1024; //maximum allowed number of dimensions
    public static int MAX_DIMS_NUMBER = 65535; //maximum allowed dimension's number

    public static class Builder extends ParametrizedFieldMapper.Builder {

        final Parameter<Map<String, String>> meta = Parameter.metaParam();

        final Version indexCreatedVersion;

        public Builder(String name, Version indexCreatedVersion) {
            super(name);
            this.indexCreatedVersion = indexCreatedVersion;
        }

        @Override
        protected List<Parameter<?>> getParameters() {
            return Collections.singletonList(meta);
        }

        @Override
        public SparseVectorFieldMapper build(BuilderContext context) {
            return new SparseVectorFieldMapper(
                    name, new SparseVectorFieldType(buildFullName(context), meta.getValue()),
                    multiFieldsBuilder.build(this, context), copyTo.build(), indexCreatedVersion);
        }
    }

    public static final TypeParser PARSER = new TypeParser((n, c) -> {
        deprecationLogger.deprecate("sparse_vector", DEPRECATION_MESSAGE);
        return new Builder(n, c.indexVersionCreated());
    });

    public static final class SparseVectorFieldType extends MappedFieldType {

        public SparseVectorFieldType(String name, Map<String, String> meta) {
            super(name, false, false, true, TextSearchInfo.NONE, meta);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public DocValueFormat docValueFormat(String format, ZoneId timeZone) {
            throw new UnsupportedOperationException(
                "Field [" + name() + "] of type [" + typeName() + "] doesn't support docvalue_fields or aggregations");
        }

        @Override
        public ValueFetcher valueFetcher(MapperService mapperService, SearchLookup searchLookup, String format) {
            if (format != null) {
                throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] doesn't support formats.");
            }
            return new SourceValueFetcher(name(), mapperService, false) {
                @Override
                protected Object parseSourceValue(Object value) {
                    return value;
                }
            };
        }

        @Override
        public Query existsQuery(QueryShardContext context) {
            return new DocValuesFieldExistsQuery(name());
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(String fullyQualifiedIndexName, Supplier<SearchLookup> searchLookup) {
            return new VectorIndexFieldData.Builder(name(), false, CoreValuesSourceType.BYTES);
        }

        @Override
        public boolean isAggregatable() {
            return false;
        }

        @Override
        public Query termQuery(Object value, QueryShardContext context) {
            throw new UnsupportedOperationException(
                "Field [" + name() + "] of type [" + typeName() + "] doesn't support queries");
        }
    }

    private final Version indexCreatedVersion;

    private SparseVectorFieldMapper(String simpleName, MappedFieldType mappedFieldType,
                                    MultiFields multiFields, CopyTo copyTo, Version indexCreatedVersion) {
        super(simpleName, mappedFieldType, multiFields, copyTo);
        this.indexCreatedVersion = indexCreatedVersion;
    }

    @Override
    protected SparseVectorFieldMapper clone() {
        return (SparseVectorFieldMapper) super.clone();
    }

    @Override
    public SparseVectorFieldType fieldType() {
        return (SparseVectorFieldType) super.fieldType();
    }

    @Override
    public void parse(ParseContext context) throws IOException {
        if (context.externalValueSet()) {
            throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] can't be used in multi-fields");
        }
        ensureExpectedToken(Token.START_OBJECT, context.parser().currentToken(), context.parser());
        int[] dims = new int[0];
        float[] values = new float[0];
        int dimCount = 0;
        int dim = 0;
        float value;
        for (Token token = context.parser().nextToken(); token != Token.END_OBJECT; token = context.parser().nextToken()) {
            if (token == Token.FIELD_NAME) {
                try {
                    dim = Integer.parseInt(context.parser().currentName());
                    if (dim < 0 || dim > MAX_DIMS_NUMBER) {
                        throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "]'s dimension number " +
                            "must be a non-negative integer value not exceeding [" + MAX_DIMS_NUMBER + "], got [" + dim + "]");
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "]'s dimensions should be " +
                        "integers represented as strings, but got [" + context.parser().currentName() + "]", e);
                }
            } else if (token == Token.VALUE_NUMBER) {
                value = context.parser().floatValue(true);
                if (dims.length <= dimCount) { // ensure arrays have enough capacity
                    values = ArrayUtil.grow(values, dimCount + 1);
                    dims = ArrayUtil.grow(dims, dimCount + 1);
                }
                dims[dimCount] = dim;
                values[dimCount] = value;
                if (dimCount++ >= MAX_DIMS_COUNT) {
                    throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() +
                        "] has exceeded the maximum allowed number of dimensions of [" + MAX_DIMS_COUNT + "]");
                }
            } else {
                throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() +
                    "] takes an object that maps a dimension number to a float, " + "but got unexpected token [" + token + "]");
            }
        }

        BytesRef br = VectorEncoderDecoder.encodeSparseVector(indexCreatedVersion, dims, values, dimCount);
        BinaryDocValuesField field = new BinaryDocValuesField(fieldType().name(), br);
        context.doc().addWithKey(fieldType().name(), field);
    }

    @Override
    protected void parseCreateField(ParseContext context) {
        throw new AssertionError("parse is implemented directly");
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public ParametrizedFieldMapper.Builder getMergeBuilder() {
        return new Builder(simpleName(), indexCreatedVersion).init(this);
    }
}
