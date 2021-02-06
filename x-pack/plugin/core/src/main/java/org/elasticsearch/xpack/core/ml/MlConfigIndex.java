/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.ml;

import org.elasticsearch.Version;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.xpack.core.template.TemplateUtils;

import java.util.Collections;

public final class MlConfigIndex {

    private static final String INDEX_NAME = ".ml-config";
    private static final String MAPPINGS_VERSION_VARIABLE = "xpack.ml.version";

    /**
     * The name of the index where job, datafeed and analytics configuration is stored
     *
     * @return The index name
     */
    public static String indexName() {
        return INDEX_NAME;
    }

    public static String mapping() {
        return mapping(MapperService.SINGLE_MAPPING_NAME);
    }

    public static String mapping(String mappingType) {
        return TemplateUtils.loadTemplate(
            "/org/elasticsearch/xpack/core/ml/config_index_mappings.json",
            Version.CURRENT.toString(),
            MAPPINGS_VERSION_VARIABLE,
            Collections.singletonMap("xpack.ml.mapping_type", mappingType));
    }

    private MlConfigIndex() {}
}
