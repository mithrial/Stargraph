package net.stargraph.core;

        /*-
         * ==========================License-Start=============================
         * Stargraph
         * --------------------------------------------------------------------
         * Copyright (C) 2017 Lambda^3
         * --------------------------------------------------------------------
         * Permission is hereby granted, free of charge, to any person obtaining a copy
         * of this software and associated documentation files (the "Software"), to deal
         * in the Software without restriction, including without limitation the rights
         * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
         * copies of the Software, and to permit persons to whom the Software is
         * furnished to do so, subject to the following conditions:
         *
         * The above copyright notice and this permission notice shall be included in
         * all copies or substantial portions of the Software.
         *
         * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
         * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
         * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
         * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
         * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
         * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
         * THE SOFTWARE.
         * ==========================License-End===============================
         */


import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import net.stargraph.StarGraphException;
import net.stargraph.core.graph.GraphSearcher;
import net.stargraph.core.impl.corenlp.NERSearcher;
import net.stargraph.core.impl.elastic.ElasticEntitySearcher;
import net.stargraph.core.impl.jena.JenaGraphSearcher;
import net.stargraph.core.index.Indexer;
import net.stargraph.core.ner.NER;
import net.stargraph.core.search.BaseSearcher;
import net.stargraph.core.search.EntitySearcher;
import net.stargraph.core.search.Searcher;
import net.stargraph.model.KBId;
import net.stargraph.query.Language;
import net.stargraph.rank.ModifiableIndraParams;
import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * An instance of a Knowledge Base and its inner core components. What else could be?
 */
public final class KBCore {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private Marker marker;

    private String kbName;
    private Config mainConfig;
    private Config kbConfig;
    private Language language;
    private KBLoader kbLoader;
    private Model graphModel;
    private Namespace namespace;
    private Stargraph stargraph;
    private NER ner;
    private Map<String, Indexer> indexers;
    private Map<String, Searcher> searchers;
    private boolean running;

    public KBCore(String kbName, Stargraph stargraph, boolean start) {
        this.kbName = Objects.requireNonNull(kbName);
        this.stargraph = Objects.requireNonNull(stargraph);
        this.mainConfig = stargraph.getMainConfig();
        this.kbConfig = mainConfig.getConfig(String.format("kb.%s", kbName));
        this.marker = MarkerFactory.getMarker(kbName);
        this.indexers = new ConcurrentHashMap<>();
        this.searchers = new ConcurrentHashMap<>();
        this.language = Language.valueOf(kbConfig.getString("language").toUpperCase());
        this.namespace = Namespace.create(kbConfig);

        if (start) {
            initialize();
        }
    }

    public synchronized void initialize() {
        if (running) {
            throw new IllegalStateException("Already started.");
        }

        final List<String> modelNames = getKBIds()
                .stream()
                .map(KBId::getModel)
                .collect(Collectors.toList());

        for (String modelId : modelNames) {
            logger.info(marker, "Initializing '{}'", modelId);
            final KBId kbId = KBId.of(kbName, modelId);
            IndicesFactory factory = stargraph.getIndicesFactory(kbId);

            Indexer indexer = factory.createIndexer(kbId, stargraph);

            if (indexer != null) {
                indexer.start();
                indexers.put(modelId, indexer);
            } else {
                logger.warn(marker, "No indexer created for {}", kbId);
            }

            BaseSearcher searcher = factory.createSearcher(kbId, stargraph);

            if (searcher != null) {
                searcher.start();
                searchers.put(modelId, searcher);
            } else {
                logger.warn(marker, "No searcher created for {}", kbId);
            }
        }

        this.ner = new NERSearcher(language, createEntitySearcher(), kbName);
        this.kbLoader = new KBLoader(this);
        this.running = true;
    }


    public synchronized void terminate() {
        if (!running) {
            throw new IllegalStateException("Already stopped.");
        }
        logger.info(marker, "Terminating '{}'", kbName);

        indexers.values().forEach(Indexer::stop);
        searchers.values().forEach(Searcher::stop);

        this.running = false;
    }

    public Config getConfig() {
        return kbConfig;
    }

    public Config getConfig(String path) {
        return kbConfig.getConfig(path);
    }

    public String getKBName() {
        return kbName;
    }

    public Language getLanguage() {
        return language;
    }

    public List<KBId> getKBIds() {
        ConfigObject typeObj = this.mainConfig.getObject(String.format("kb.%s.model", kbName));
        return typeObj.keySet().stream().map(modelName -> KBId.of(kbName, modelName)).collect(Collectors.toList());
    }

    public Model getGraphModel() {
        checkRunning();
        return stargraph.getGraphModelFactory().getModel(kbName);
    }

    public Indexer getIndexer(String modelId) {
        checkRunning();
        if (indexers.containsKey(modelId)) {
            return indexers.get(modelId);
        }
        throw new StarGraphException("Indexer not found nor initialized: " + KBId.of(kbName, modelId));
    }

    public Searcher getSearcher(String modelId) {
        checkRunning();
        if (searchers.containsKey(modelId)) {
            return searchers.get(modelId);
        }
        throw new StarGraphException("Searcher not found nor initialized: " + KBId.of(kbName, modelId));
    }

    public KBLoader getLoader() {
        checkRunning();
        return kbLoader;
    }

    public NER getNER() {
        return ner;
    }

    public Namespace getNamespace() {
        return namespace;
    }

    public EntitySearcher createEntitySearcher() {
        //TODO: EntitySearcher creation depends on Searcher impl, need to decouple. See how NERAndLinkingIT is failing.
        return new ElasticEntitySearcher(this);
    }


    public GraphSearcher createGraphSearcher() {
        return new JenaGraphSearcher(kbName, stargraph);
    }

    public void configureDistributionalParams(ModifiableIndraParams params) {
        String indraUrl = stargraph.getMainConfig().getString("distributional-service.rest-url");
        String indraCorpus = stargraph.getMainConfig().getString("distributional-service.corpus");
        params.url(indraUrl).corpus(indraCorpus).language(language.code);
    }

    private void checkRunning() {
        if (!running) {
            throw new IllegalStateException("KB Core not started.");
        }
    }
}