/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.vectors;

import org.elasticsearch.common.inject.Module;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xpack.core.XPackPlugin;
import org.elasticsearch.xpack.vectors.mapper.DenseVectorFieldMapper;
import org.elasticsearch.xpack.vectors.mapper.SparseVectorFieldMapper;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class Vectors extends Plugin implements MapperPlugin {

    public static final String NAME = "vectors";

    public Vectors() { }

    public Collection<Module> createGuiceModules() {
        return Collections.singletonList(b -> {
            XPackPlugin.bindFeatureSet(b, VectorsFeatureSet.class);
        });
    }

    @Override
    public Map<String, Mapper.TypeParser> getMappers() {
        Map<String, Mapper.TypeParser> mappers = new LinkedHashMap<>();
        mappers.put(DenseVectorFieldMapper.CONTENT_TYPE, DenseVectorFieldMapper.PARSER);
        mappers.put(SparseVectorFieldMapper.CONTENT_TYPE, SparseVectorFieldMapper.PARSER);
        return Collections.unmodifiableMap(mappers);
    }
}
