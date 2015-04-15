/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.condition.script;

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.elasticsearch.script.ScriptEngineService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.groovy.GroovyScriptEngineService;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.watcher.execution.WatchExecutionContext;
import org.elasticsearch.watcher.support.Script;
import org.elasticsearch.watcher.support.init.proxy.ScriptServiceProxy;
import org.elasticsearch.watcher.watch.Payload;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.watcher.test.WatcherTestUtils.mockExecutionContext;

/**
 */
public class ScriptConditionTests extends ElasticsearchTestCase {

    ThreadPool tp = null;

    @Before
    public void init() {
        tp = new ThreadPool(ThreadPool.Names.SAME);
    }

    @After
    public void cleanup() {
        tp.shutdownNow();
    }


    @Test
    public void testExecute() throws Exception {
        ScriptServiceProxy scriptService = getScriptServiceProxy(tp);
        ExecutableScriptCondition condition = new ExecutableScriptCondition(new ScriptCondition(new Script("ctx.payload.hits.total > 1")), logger, scriptService);
        SearchResponse response = new SearchResponse(InternalSearchResponse.empty(), "", 3, 3, 500l, new ShardSearchFailure[0]);
        WatchExecutionContext ctx = mockExecutionContext("_name", new Payload.XContent(response));
        assertFalse(condition.execute(ctx).met());
    }

    @Test
    public void testExecute_MergedParams() throws Exception {
        ScriptServiceProxy scriptService = getScriptServiceProxy(tp);
        Script script = new Script("ctx.payload.hits.total > threshold", ScriptService.ScriptType.INLINE, ScriptService.DEFAULT_LANG, ImmutableMap.<String, Object>of("threshold", 1));
        ExecutableScriptCondition executable = new ExecutableScriptCondition(new ScriptCondition(script), logger, scriptService);
        SearchResponse response = new SearchResponse(InternalSearchResponse.empty(), "", 3, 3, 500l, new ShardSearchFailure[0]);
        WatchExecutionContext ctx = mockExecutionContext("_name", new Payload.XContent(response));
        assertFalse(executable.execute(ctx).met());
    }

    @Test
    @Repeat(iterations =  5)
    public void testParser_Valid() throws Exception {
        ScriptConditionFactory factory = new ScriptConditionFactory(ImmutableSettings.settingsBuilder().build(), getScriptServiceProxy(tp));

        XContentBuilder builder;
        if (randomBoolean()) {
            //Create structure
            builder = createConditionContent("ctx.payload.hits.total > 1", null, null);
        } else {
            //Create simple { "script : "ctx.payload.hits.total" } which should parse
            builder = XContentFactory.jsonBuilder();
            builder.startObject();
            builder.field("script", "ctx.payload.hits.total > 1");
            builder.endObject();
        }

        XContentParser parser = XContentFactory.xContent(builder.bytes()).createParser(builder.bytes());
        parser.nextToken();
        ScriptCondition condition = factory.parseCondition("_watch", parser);
        ExecutableScriptCondition executable = factory.createExecutable(condition);

        SearchResponse response = new SearchResponse(InternalSearchResponse.empty(), "", 3, 3, 500l, new ShardSearchFailure[0]);
        WatchExecutionContext ctx = mockExecutionContext("_name", new Payload.XContent(response));

        assertFalse(executable.execute(ctx).met());


        builder = createConditionContent("return true", null, null);
        parser = XContentFactory.xContent(builder.bytes()).createParser(builder.bytes());
        parser.nextToken();
        condition = factory.parseCondition("_watch", parser);
        executable = factory.createExecutable(condition);

        ctx = mockExecutionContext("_name", new Payload.XContent(response));

        assertTrue(executable.execute(ctx).met());
    }

    @Test(expected = ScriptConditionException.class)
    public void testParser_InValid() throws Exception {
        ScriptConditionFactory factory = new ScriptConditionFactory(ImmutableSettings.settingsBuilder().build(), getScriptServiceProxy(tp));
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject().endObject();
        XContentParser parser = XContentFactory.xContent(builder.bytes()).createParser(builder.bytes());
        parser.nextToken();
        factory.parseCondition("_id", parser);
        fail("expected a condition exception trying to parse an invalid condition XContent");
    }


    @Test
    public void testScriptResultParser_Valid() throws Exception {
        ScriptConditionFactory conditionParser = new ScriptConditionFactory(ImmutableSettings.settingsBuilder().build(), getScriptServiceProxy(tp));

        XContentBuilder builder = jsonBuilder();
        builder.startObject();
        builder.field("met", true);
        builder.endObject();

        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();
        ScriptCondition.Result scriptResult = conditionParser.parseResult("_id", parser);
        assertTrue(scriptResult.met());

        builder = jsonBuilder();
        builder.startObject();
        builder.field("met", false);
        builder.endObject();

        parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();
        scriptResult = conditionParser.parseResult("_id", parser);
        assertFalse(scriptResult.met());
    }

    @Test(expected = ScriptConditionException.class)
    public void testScriptResultParser_Invalid() throws Exception {
        ScriptConditionFactory conditionParser = new ScriptConditionFactory(ImmutableSettings.settingsBuilder().build(), getScriptServiceProxy(tp));

        XContentBuilder builder = jsonBuilder();
        builder.startObject().endObject();

        conditionParser.parseResult("_id", XContentFactory.xContent(builder.bytes()).createParser(builder.bytes()));
        fail("expected a condition exception trying to parse an invalid condition XContent");
    }

    private static ScriptServiceProxy getScriptServiceProxy(ThreadPool tp) throws IOException {
        Settings settings = ImmutableSettings.settingsBuilder()
                .put(ScriptService.DISABLE_DYNAMIC_SCRIPTING_SETTING, "none")
                .build();
        GroovyScriptEngineService groovyScriptEngineService = new GroovyScriptEngineService(settings);
        Set<ScriptEngineService> engineServiceSet = new HashSet<>();
        engineServiceSet.add(groovyScriptEngineService);
        NodeSettingsService nodeSettingsService = new NodeSettingsService(settings);
        return ScriptServiceProxy.of(new ScriptService(settings, new Environment(), engineServiceSet, new ResourceWatcherService(settings, tp), nodeSettingsService));
    }

    private static XContentBuilder createConditionContent(String script, String scriptLang, ScriptService.ScriptType scriptType) throws IOException {
        XContentBuilder builder = jsonBuilder().startObject();
        builder.field("script", script);
        if (scriptLang != null) {
            builder.field("lang", scriptLang);
        }
        if (scriptType != null) {
            builder.field("type", scriptType.toString());
        }
        return builder.endObject();
    }


}
