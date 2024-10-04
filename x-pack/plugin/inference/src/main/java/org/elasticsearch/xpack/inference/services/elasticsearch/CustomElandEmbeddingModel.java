/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.elasticsearch;

import org.elasticsearch.inference.TaskType;

public class CustomElandEmbeddingModel extends CustomElandModel {

    public CustomElandEmbeddingModel(
        String inferenceEntityId,
        TaskType taskType,
        String service,
        CustomElandInternalTextEmbeddingServiceSettings serviceSettings,
        String endpointVersion
    ) {
        super(inferenceEntityId, taskType, service, serviceSettings, endpointVersion);
    }

    @Override
    public CustomElandInternalTextEmbeddingServiceSettings getServiceSettings() {
        return (CustomElandInternalTextEmbeddingServiceSettings) super.getServiceSettings();
    }
}
