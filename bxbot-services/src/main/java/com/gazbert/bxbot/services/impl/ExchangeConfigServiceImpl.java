/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Gareth Jon Lynch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.gazbert.bxbot.services.impl;

import com.gazbert.bxbot.domain.exchange.ExchangeConfig;
import com.gazbert.bxbot.repository.ExchangeConfigRepository;
import com.gazbert.bxbot.services.ExchangeConfigService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/**
 * TODO Strip out Auth info!
 *
 * Implementation of the Exchange config service.
 *
 * @author gazbert
 */
@Service("exchangeConfigService")
@Transactional
@ComponentScan(basePackages = {"com.gazbert.bxbot.repository"})
public class ExchangeConfigServiceImpl implements ExchangeConfigService {

    private static final Logger LOG = LogManager.getLogger();

    private final ExchangeConfigRepository exchangeConfigRepository;

    @Autowired
    public ExchangeConfigServiceImpl(ExchangeConfigRepository exchangeConfigRepository) {
        Assert.notNull(exchangeConfigRepository, "exchangeConfigRepository dependency cannot be null!");
        this.exchangeConfigRepository = exchangeConfigRepository;
    }

    @Override
    public ExchangeConfig getConfig() {
        return exchangeConfigRepository.getConfig();
    }

    @Override
    public void updateConfig(ExchangeConfig config) {
        LOG.info(() -> "About to update: " + config);
        exchangeConfigRepository.updateConfig(config);
    }
}