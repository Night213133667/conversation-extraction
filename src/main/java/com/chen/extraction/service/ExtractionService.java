package com.chen.extraction.service;

import com.chen.extraction.model.Conversation;
import com.chen.extraction.model.ExtractionResult;

/**
 * 对话信息提取服务接口
 */
public interface ExtractionService {

    /**
     * 从单条对话中提取结构化信息
     *
     * @param conversation 原始对话数据
     * @return 提取结果
     */
    ExtractionResult extract(Conversation conversation);
}
