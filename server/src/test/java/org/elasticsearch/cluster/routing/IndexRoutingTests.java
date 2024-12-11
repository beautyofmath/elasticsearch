/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.cluster.routing;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.StringHelper;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.RoutingMissingException;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.index.IndexVersionUtils;
import org.elasticsearch.xcontent.DeprecationHandler;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.xcontent.support.MapXContentParser;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public class IndexRoutingTests extends ESTestCase {
    public void testSimpleRoutingRejectsEmptyId() {
        IndexRouting indexRouting = IndexRouting.fromIndexMetadata(
            IndexMetadata.builder("test").settings(settings(IndexVersion.current())).numberOfShards(2).numberOfReplicas(1).build()
        );
        IndexRequest req = new IndexRequest().id("");
        Exception e = expectThrows(IllegalArgumentException.class, () -> indexRouting.preProcess(req));
        assertThat(e.getMessage(), equalTo("if _id is specified it must not be empty"));
    }

    public void testSimpleRoutingAcceptsId() {
        IndexRouting indexRouting = IndexRouting.fromIndexMetadata(
            IndexMetadata.builder("test").settings(settings(IndexVersion.current())).numberOfShards(2).numberOfReplicas(1).build()
        );
        String id = randomAlphaOfLength(10);
        IndexRequest req = new IndexRequest().id(id);
        indexRouting.preProcess(req);
        assertThat(req.id(), equalTo(id));
        assertThat(req.getAutoGeneratedTimestamp(), equalTo(IndexRequest.UNSET_AUTO_GENERATED_TIMESTAMP));
    }

    public void testSimpleRoutingAssignedRandomId() {
        IndexRouting indexRouting = IndexRouting.fromIndexMetadata(
            IndexMetadata.builder("test").settings(settings(IndexVersion.current())).numberOfShards(2).numberOfReplicas(1).build()
        );
        IndexRequest req = new IndexRequest();
        indexRouting.preProcess(req);
        assertThat(req.id(), not(nullValue()));
        assertThat(req.getAutoGeneratedTimestamp(), not(equalTo(IndexRequest.UNSET_AUTO_GENERATED_TIMESTAMP)));
    }

    public void testGenerateShardId() {
        int[][] possibleValues = new int[][] { { 8, 4, 2 }, { 20, 10, 2 }, { 36, 12, 3 }, { 15, 5, 1 } };
        for (int i = 0; i < 10; i++) {
            int[] shardSplits = randomFrom(possibleValues);
            assertEquals(shardSplits[0], (shardSplits[0] / shardSplits[1]) * shardSplits[1]);
            assertEquals(shardSplits[1], (shardSplits[1] / shardSplits[2]) * shardSplits[2]);
            IndexMetadata metadata = IndexMetadata.builder("test")
                .settings(settings(IndexVersion.current()))
                .numberOfShards(shardSplits[0])
                .numberOfReplicas(1)
                .build();
            String term = randomAlphaOfLength(10);
            final int shard = shardIdFromSimple(IndexRouting.fromIndexMetadata(metadata), term, null);
            IndexMetadata shrunk = IndexMetadata.builder("test")
                .settings(settings(IndexVersion.current()))
                .numberOfShards(shardSplits[1])
                .numberOfReplicas(1)
                .setRoutingNumShards(shardSplits[0])
                .build();
            int shrunkShard = shardIdFromSimple(IndexRouting.fromIndexMetadata(shrunk), term, null);

            Set<ShardId> shardIds = IndexMetadata.selectShrinkShards(shrunkShard, metadata, shrunk.getNumberOfShards());
            assertEquals(1, shardIds.stream().filter((sid) -> sid.id() == shard).count());

            shrunk = IndexMetadata.builder("test")
                .settings(settings(IndexVersion.current()))
                .numberOfShards(shardSplits[2])
                .numberOfReplicas(1)
                .setRoutingNumShards(shardSplits[0])
                .build();
            shrunkShard = shardIdFromSimple(IndexRouting.fromIndexMetadata(shrunk), term, null);
            shardIds = IndexMetadata.selectShrinkShards(shrunkShard, metadata, shrunk.getNumberOfShards());
            assertEquals(Arrays.toString(shardSplits), 1, shardIds.stream().filter((sid) -> sid.id() == shard).count());
        }
    }

    public void testGenerateShardIdSplit() {
        int[][] possibleValues = new int[][] { { 2, 4, 8 }, { 2, 10, 20 }, { 3, 12, 36 }, { 1, 5, 15 } };
        for (int i = 0; i < 10; i++) {
            int[] shardSplits = randomFrom(possibleValues);
            assertEquals(shardSplits[0], (shardSplits[0] * shardSplits[1]) / shardSplits[1]);
            assertEquals(shardSplits[1], (shardSplits[1] * shardSplits[2]) / shardSplits[2]);
            IndexMetadata metadata = IndexMetadata.builder("test")
                .settings(settings(IndexVersion.current()))
                .numberOfShards(shardSplits[0])
                .numberOfReplicas(1)
                .setRoutingNumShards(shardSplits[2])
                .build();
            String term = randomAlphaOfLength(10);
            final int shard = shardIdFromSimple(IndexRouting.fromIndexMetadata(metadata), term, null);
            IndexMetadata split = IndexMetadata.builder("test")
                .settings(settings(IndexVersion.current()))
                .numberOfShards(shardSplits[1])
                .numberOfReplicas(1)
                .setRoutingNumShards(shardSplits[2])
                .build();
            int shrunkShard = shardIdFromSimple(IndexRouting.fromIndexMetadata(split), term, null);

            ShardId shardId = IndexMetadata.selectSplitShard(shrunkShard, metadata, split.getNumberOfShards());
            assertNotNull(shardId);
            assertEquals(shard, shardId.getId());

            split = IndexMetadata.builder("test")
                .settings(settings(IndexVersion.current()))
                .numberOfShards(shardSplits[2])
                .numberOfReplicas(1)
                .setRoutingNumShards(shardSplits[2])
                .build();
            shrunkShard = shardIdFromSimple(IndexRouting.fromIndexMetadata(split), term, null);
            shardId = IndexMetadata.selectSplitShard(shrunkShard, metadata, split.getNumberOfShards());
            assertNotNull(shardId);
            assertEquals(shard, shardId.getId());
        }
    }

    public void testCollectSearchShardsInStandardIndex() {
        for (int shards = 1; shards < 5; shards++) {
            IndexRouting indexRouting = IndexRouting.fromIndexMetadata(
                IndexMetadata.builder("test").settings(settings(IndexVersion.current())).numberOfShards(shards).numberOfReplicas(1).build()
            );

            for (int i = 0; i < 20; i++) {
                String routing = randomUnicodeOfLengthBetween(1, 50);

                Set<Integer> searchShardSet = new HashSet<>();
                indexRouting.collectSearchShards(routing, searchShardSet::add);
                assertThat(searchShardSet, hasSize(1));
            }
        }
    }

    public void testPartitionedIndex() {
        /*
         * make sure the same routing value always has each _id fall within the
         * configured partition size
         */
        for (int shards = 1; shards < 5; shards++) {
            for (int partitionSize = 1; partitionSize == 1 || partitionSize < shards; partitionSize++) {
                IndexRouting indexRouting = IndexRouting.fromIndexMetadata(
                    IndexMetadata.builder("test")
                        .settings(settings(IndexVersion.current()))
                        .numberOfShards(shards)
                        .routingPartitionSize(partitionSize)
                        .numberOfReplicas(1)
                        .build()
                );

                for (int i = 0; i < 20; i++) {
                    String routing = randomUnicodeOfLengthBetween(1, 50);

                    Set<Integer> shardSet = new HashSet<>();
                    for (int k = 0; k < 150; k++) {
                        String id = randomUnicodeOfLengthBetween(1, 50);
                        shardSet.add(shardIdFromSimple(indexRouting, id, routing));
                    }
                    assertThat(shardSet, hasSize(partitionSize));

                    Set<Integer> searchShardSet = new HashSet<>();
                    indexRouting.collectSearchShards(routing, searchShardSet::add);
                    assertThat(searchShardSet, hasSize(partitionSize));
                }
            }
        }
    }

    public void testPartitionedIndexShrunk() {
        Map<String, Map<String, Integer>> routingIdToShard = new HashMap<>();

        Map<String, Integer> routingA = new HashMap<>();
        routingA.put("a_0", 1);
        routingA.put("a_1", 2);
        routingA.put("a_2", 2);
        routingA.put("a_3", 2);
        routingA.put("a_4", 1);
        routingA.put("a_5", 2);
        routingIdToShard.put("a", routingA);

        Map<String, Integer> routingB = new HashMap<>();
        routingB.put("b_0", 0);
        routingB.put("b_1", 0);
        routingB.put("b_2", 0);
        routingB.put("b_3", 0);
        routingB.put("b_4", 3);
        routingB.put("b_5", 3);
        routingIdToShard.put("b", routingB);

        Map<String, Integer> routingC = new HashMap<>();
        routingC.put("c_0", 1);
        routingC.put("c_1", 1);
        routingC.put("c_2", 0);
        routingC.put("c_3", 0);
        routingC.put("c_4", 0);
        routingC.put("c_5", 1);
        routingIdToShard.put("c", routingC);

        Map<String, Integer> routingD = new HashMap<>();
        routingD.put("d_0", 2);
        routingD.put("d_1", 2);
        routingD.put("d_2", 3);
        routingD.put("d_3", 3);
        routingD.put("d_4", 3);
        routingD.put("d_5", 3);
        routingIdToShard.put("d", routingD);

        IndexRouting indexRouting = IndexRouting.fromIndexMetadata(
            IndexMetadata.builder("test")
                .settings(settings(IndexVersion.current()))
                .setRoutingNumShards(8)
                .numberOfShards(4)
                .routingPartitionSize(3)
                .numberOfReplicas(1)
                .build()
        );

        for (Map.Entry<String, Map<String, Integer>> routingIdEntry : routingIdToShard.entrySet()) {
            String routing = routingIdEntry.getKey();

            for (Map.Entry<String, Integer> idEntry : routingIdEntry.getValue().entrySet()) {
                String id = idEntry.getKey();
                int shard = idEntry.getValue();

                assertEquals(shard, shardIdFromSimple(indexRouting, id, routing));
            }
        }
    }

    public void testPartitionedIndexBWC() {
        Map<String, Map<String, Integer>> routingIdToShard = new HashMap<>();

        Map<String, Integer> routingA = new HashMap<>();
        routingA.put("a_0", 3);
        routingA.put("a_1", 2);
        routingA.put("a_2", 2);
        routingA.put("a_3", 3);
        routingIdToShard.put("a", routingA);

        Map<String, Integer> routingB = new HashMap<>();
        routingB.put("b_0", 5);
        routingB.put("b_1", 0);
        routingB.put("b_2", 0);
        routingB.put("b_3", 0);
        routingIdToShard.put("b", routingB);

        Map<String, Integer> routingC = new HashMap<>();
        routingC.put("c_0", 4);
        routingC.put("c_1", 4);
        routingC.put("c_2", 3);
        routingC.put("c_3", 4);
        routingIdToShard.put("c", routingC);

        Map<String, Integer> routingD = new HashMap<>();
        routingD.put("d_0", 3);
        routingD.put("d_1", 4);
        routingD.put("d_2", 4);
        routingD.put("d_3", 4);
        routingIdToShard.put("d", routingD);

        IndexRouting indexRouting = IndexRouting.fromIndexMetadata(
            IndexMetadata.builder("test")
                .settings(settings(IndexVersion.current()))
                .numberOfShards(6)
                .routingPartitionSize(2)
                .numberOfReplicas(1)
                .build()
        );

        for (Map.Entry<String, Map<String, Integer>> routingIdEntry : routingIdToShard.entrySet()) {
            String routing = routingIdEntry.getKey();

            for (Map.Entry<String, Integer> idEntry : routingIdEntry.getValue().entrySet()) {
                String id = idEntry.getKey();
                int shard = idEntry.getValue();

                assertEquals(shard, shardIdFromSimple(indexRouting, id, routing));
            }
        }
    }

    /**
     * Ensures that all changes to the hash-function / shard selection are BWC
     */
    public void testBWC() {
        Map<String, Integer> termToShard = new TreeMap<>();
        termToShard.put("sEERfFzPSI", 1);
        termToShard.put("cNRiIrjzYd", 7);
        termToShard.put("BgfLBXUyWT", 5);
        termToShard.put("cnepjZhQnb", 3);
        termToShard.put("OKCmuYkeCK", 6);
        termToShard.put("OutXGRQUja", 5);
        termToShard.put("yCdyocKWou", 1);
        termToShard.put("KXuNWWNgVj", 2);
        termToShard.put("DGJOYrpESx", 4);
        termToShard.put("upLDybdTGs", 5);
        termToShard.put("yhZhzCPQby", 1);
        termToShard.put("EyCVeiCouA", 1);
        termToShard.put("tFyVdQauWR", 6);
        termToShard.put("nyeRYDnDQr", 6);
        termToShard.put("hswhrppvDH", 0);
        termToShard.put("BSiWvDOsNE", 5);
        termToShard.put("YHicpFBSaY", 1);
        termToShard.put("EquPtdKaBZ", 4);
        termToShard.put("rSjLZHCDfT", 5);
        termToShard.put("qoZALVcite", 7);
        termToShard.put("yDCCPVBiCm", 7);
        termToShard.put("ngizYtQgGK", 5);
        termToShard.put("FYQRIBcNqz", 0);
        termToShard.put("EBzEDAPODe", 2);
        termToShard.put("YePigbXgKb", 1);
        termToShard.put("PeGJjomyik", 3);
        termToShard.put("cyQIvDmyYD", 7);
        termToShard.put("yIEfZrYfRk", 5);
        termToShard.put("kblouyFUbu", 7);
        termToShard.put("xvIGbRiGJF", 3);
        termToShard.put("KWimwsREPf", 4);
        termToShard.put("wsNavvIcdk", 7);
        termToShard.put("xkWaPcCmpT", 0);
        termToShard.put("FKKTOnJMDy", 7);
        termToShard.put("RuLzobYixn", 2);
        termToShard.put("mFohLeFRvF", 4);
        termToShard.put("aAMXnamRJg", 7);
        termToShard.put("zKBMYJDmBI", 0);
        termToShard.put("ElSVuJQQuw", 7);
        termToShard.put("pezPtTQAAm", 7);
        termToShard.put("zBjjNEjAex", 2);
        termToShard.put("PGgHcLNPYX", 7);
        termToShard.put("hOkpeQqTDF", 3);
        termToShard.put("chZXraUPBH", 7);
        termToShard.put("FAIcSmmNXq", 5);
        termToShard.put("EZmDicyayC", 0);
        termToShard.put("GRIueBeIyL", 7);
        termToShard.put("qCChjGZYLp", 3);
        termToShard.put("IsSZQwwnUT", 3);
        termToShard.put("MGlxLFyyCK", 3);
        termToShard.put("YmscwrKSpB", 0);
        termToShard.put("czSljcjMop", 5);
        termToShard.put("XhfGWwNlng", 1);
        termToShard.put("cWpKJjlzgj", 7);
        termToShard.put("eDzIfMKbvk", 1);
        termToShard.put("WFFWYBfnTb", 0);
        termToShard.put("oDdHJxGxja", 7);
        termToShard.put("PDOQQqgIKE", 1);
        termToShard.put("bGEIEBLATe", 6);
        termToShard.put("xpRkJPWVpu", 2);
        termToShard.put("kTwZnPEeIi", 2);
        termToShard.put("DifcuqSsKk", 1);
        termToShard.put("CEmLmljpXe", 5);
        termToShard.put("cuNKtLtyJQ", 7);
        termToShard.put("yNjiAnxAmt", 5);
        termToShard.put("bVDJDCeaFm", 2);
        termToShard.put("vdnUhGLFtl", 0);
        termToShard.put("LnqSYezXbr", 5);
        termToShard.put("EzHgydDCSR", 3);
        termToShard.put("ZSKjhJlcpn", 1);
        termToShard.put("WRjUoZwtUz", 3);
        termToShard.put("RiBbcCdIgk", 4);
        termToShard.put("yizTqyjuDn", 4);
        termToShard.put("QnFjcpcZUT", 4);
        termToShard.put("agYhXYUUpl", 7);
        termToShard.put("UOjiTugjNC", 7);
        termToShard.put("nICGuWTdfV", 0);
        termToShard.put("NrnSmcnUVF", 2);
        termToShard.put("ZSzFcbpDqP", 3);
        termToShard.put("YOhahLSzzE", 5);
        termToShard.put("iWswCilUaT", 1);
        termToShard.put("zXAamKsRwj", 2);
        termToShard.put("aqGsrUPHFq", 5);
        termToShard.put("eDItImYWTS", 1);
        termToShard.put("JAYDZMRcpW", 4);
        termToShard.put("lmvAaEPflK", 7);
        termToShard.put("IKuOwPjKCx", 5);
        termToShard.put("schsINzlYB", 1);
        termToShard.put("OqbFNxrKrF", 2);
        termToShard.put("QrklDfvEJU", 6);
        termToShard.put("VLxKRKdLbx", 4);
        termToShard.put("imoydNTZhV", 1);
        termToShard.put("uFZyTyOMRO", 4);
        termToShard.put("nVAZVMPNNx", 3);
        termToShard.put("rPIdESYaAO", 5);
        termToShard.put("nbZWPWJsIM", 0);
        termToShard.put("wRZXPSoEgd", 3);
        termToShard.put("nGzpgwsSBc", 4);
        termToShard.put("AITyyoyLLs", 4);
        IndexRouting indexRouting = IndexRouting.fromIndexMetadata(
            IndexMetadata.builder("test").settings(settings(IndexVersion.current())).numberOfShards(8).numberOfReplicas(1).build()
        );
        for (Map.Entry<String, Integer> entry : termToShard.entrySet()) {
            String key = entry.getKey();
            int shardId;
            switch (between(0, 2)) {
                case 0 -> shardId = shardIdFromSimple(indexRouting, key, null);
                case 1 -> shardId = shardIdFromSimple(indexRouting, randomAlphaOfLength(5), key);
                case 2 -> {
                    AtomicInteger s = new AtomicInteger(-1);
                    indexRouting.collectSearchShards(key, r -> {
                        int old = s.getAndSet(r);
                        assertThat("only called once", old, equalTo(-1));
                    });
                    shardId = s.get();
                }
                default -> throw new AssertionError("invalid option");
            }
            assertEquals(shardId, entry.getValue().intValue());
        }
    }

    public void testRequiredRouting() {
        IndexRouting indexRouting = IndexRouting.fromIndexMetadata(
            IndexMetadata.builder("test")
                .settings(settings(IndexVersion.current()))
                .numberOfShards(2)
                .numberOfReplicas(1)
                .putMapping("{\"_routing\":{\"required\": true}}")
                .build()
        );
        Exception e = expectThrows(RoutingMissingException.class, () -> shardIdFromSimple(indexRouting, "id", null));
        assertThat(e.getMessage(), equalTo("routing is required for [test]/[id]"));
    }

    /**
     * Extract a shardId from a "simple" {@link IndexRouting} using a randomly
     * chosen method. All of the random methods <strong>should</strong> return the
     * same results.
     */
    private int shardIdFromSimple(IndexRouting indexRouting, String id, @Nullable String routing) {
        return switch (between(0, 3)) {
            case 0 -> indexRouting.indexShard(id, routing, null, null);
            case 1 -> indexRouting.updateShard(id, routing);
            case 2 -> indexRouting.deleteShard(id, routing);
            case 3 -> indexRouting.getShard(id, routing);
            default -> throw new AssertionError("invalid option");
        };
    }

    public void testRoutingAllowsId() {
        IndexRouting indexRouting = indexRoutingForPath(between(1, 5), randomAlphaOfLength(5));
        String id = randomAlphaOfLength(5);
        IndexRequest req = new IndexRequest().id(id);
        indexRouting.preProcess(req);
        assertThat(req.id(), equalTo(id));
    }

    /**
     * {@code routing_path} based {@link IndexRouting} implementations do
     * not assign an {@code _id} on the coordinating node, instead they
     * rely on the {@link IdFieldMapper} implementation to assign the
     * {@code _id} as part of parsing the document.
     */
    public void testRoutingPathLeavesIdNull() {
        IndexRouting indexRouting = indexRoutingForPath(between(1, 5), randomAlphaOfLength(5));
        IndexRequest req = new IndexRequest();
        indexRouting.preProcess(req);
        assertThat(req.id(), nullValue());
    }

    public void testRoutingPathEmptySource() throws IOException {
        IndexRouting routing = indexRoutingForPath(between(1, 5), randomAlphaOfLength(5));
        Exception e = expectThrows(
            IllegalArgumentException.class,
            () -> routing.indexShard(randomAlphaOfLength(5), null, XContentType.JSON, source(Map.of()))
        );
        assertThat(e.getMessage(), equalTo("Error extracting routing: source didn't contain any routing fields"));
    }

    public void testRoutingPathMismatchSource() throws IOException {
        IndexRouting routing = indexRoutingForPath(between(1, 5), "foo");
        Exception e = expectThrows(
            IllegalArgumentException.class,
            () -> routing.indexShard(randomAlphaOfLength(5), null, XContentType.JSON, source(Map.of("bar", "dog")))
        );
        assertThat(e.getMessage(), equalTo("Error extracting routing: source didn't contain any routing fields"));
    }

    public void testRoutingPathUpdate() throws IOException {
        IndexRouting routing = indexRoutingForPath(between(1, 5), "foo");
        Exception e = expectThrows(
            IllegalArgumentException.class,
            () -> routing.updateShard(randomAlphaOfLength(5), randomBoolean() ? null : randomAlphaOfLength(5))
        );
        assertThat(e.getMessage(), equalTo("update is not supported because the destination index [test] is in time series mode"));
    }

    public void testRoutingIndexWithRouting() throws IOException {
        IndexRouting indexRouting = indexRoutingForPath(5, "foo");
        String value = randomAlphaOfLength(5);
        BytesReference source = source(Map.of("foo", value));
        String docRouting = randomAlphaOfLength(5);
        Exception e = expectThrows(
            IllegalArgumentException.class,
            () -> indexRouting.indexShard(randomAlphaOfLength(5), docRouting, XContentType.JSON, source)
        );
        assertThat(
            e.getMessage(),
            equalTo("specifying routing is not supported because the destination index [test] is in time series mode")
        );
    }

    public void testRoutingPathCollectSearchWithRouting() throws IOException {
        IndexRouting routing = indexRoutingForPath(between(1, 5), "foo");
        Exception e = expectThrows(IllegalArgumentException.class, () -> routing.collectSearchShards(randomAlphaOfLength(5), null));
        assertThat(
            e.getMessage(),
            equalTo("searching with a specified routing is not supported because the destination index [test] is in time series mode")
        );
    }

    public void testRoutingPathOneTopLevel() throws IOException {
        int shards = between(2, 1000);
        IndexRouting routing = indexRoutingForPath(shards, "foo");
        assertIndexShard(routing, Map.of("foo", "cat", "bar", "dog"), Math.floorMod(hash(List.of("foo", "cat")), shards));
    }

    public void testRoutingPathManyTopLevel() throws IOException {
        int shards = between(2, 1000);
        IndexRouting routing = indexRoutingForPath(shards, "f*");
        assertIndexShard(
            routing,
            Map.of("foo", "cat", "bar", "dog", "foa", "a", "fob", "b"),
            Math.floorMod(hash(List.of("foa", "a", "fob", "b", "foo", "cat")), shards) // Note that the fields are sorted
        );
    }

    public void testRoutingPathOneSub() throws IOException {
        int shards = between(2, 1000);
        IndexRouting routing = indexRoutingForPath(shards, "foo.*");
        assertIndexShard(
            routing,
            Map.of("foo", Map.of("bar", "cat"), "baz", "dog"),
            Math.floorMod(hash(List.of("foo.bar", "cat")), shards)
        );
    }

    public void testRoutingPathManySubs() throws IOException {
        int shards = between(2, 1000);
        IndexRouting routing = indexRoutingForPath(shards, "foo.*,bar.*,baz.*");
        assertIndexShard(
            routing,
            Map.of("foo", Map.of("a", "cat"), "bar", Map.of("thing", "yay", "this", "too")),
            Math.floorMod(hash(List.of("bar.thing", "yay", "bar.this", "too", "foo.a", "cat")), shards)
        );
    }

    public void testRoutingPathDotInName() throws IOException {
        int shards = between(2, 1000);
        IndexRouting routing = indexRoutingForPath(shards, "foo.bar");
        assertIndexShard(routing, Map.of("foo.bar", "cat", "baz", "dog"), Math.floorMod(hash(List.of("foo.bar", "cat")), shards));
    }

    public void testRoutingPathNumbersInSource() throws IOException {
        int shards = between(2, 1000);
        IndexRouting routing = indexRoutingForPath(shards, "foo");
        long randomLong = randomLong();
        assertIndexShard(routing, Map.of("foo", randomLong), Math.floorMod(hash(List.of("foo", Long.toString(randomLong))), shards));
        double randomDouble = randomDouble();
        assertIndexShard(routing, Map.of("foo", randomDouble), Math.floorMod(hash(List.of("foo", Double.toString(randomDouble))), shards));
        assertIndexShard(routing, Map.of("foo", 123), Math.floorMod(hash(List.of("foo", "123")), shards));
    }

    public void testRoutingPathBooleansInSource() throws IOException {
        int shards = between(2, 1000);
        IndexRouting routing = indexRoutingForPath(shards, "foo");
        assertIndexShard(routing, Map.of("foo", true), Math.floorMod(hash(List.of("foo", "true")), shards));
        assertIndexShard(routing, Map.of("foo", false), Math.floorMod(hash(List.of("foo", "false")), shards));
    }

    public void testRoutingPathArraysInSource() throws IOException {
        int shards = between(2, 1000);
        IndexRouting routing = indexRoutingForPath(shards, "a,b,c,d");
        assertIndexShard(
            routing,
            Map.of("c", List.of(true), "d", List.of(), "a", List.of("foo", "bar", "foo"), "b", List.of(21, 42)),
            // Note that the fields are sorted
            Math.floorMod(hash(List.of("a", "foo", "a", "bar", "a", "foo", "b", "21", "b", "42", "c", "true")), shards)
        );
    }

    public void testRoutingPathObjectArraysInSource() throws IOException {
        int shards = between(2, 1000);
        IndexRouting routing = indexRoutingForPath(shards, "a");

        BytesReference source = source(Map.of("a", List.of("foo", Map.of("foo", "bar"))));
        Exception e = expectThrows(
            IllegalArgumentException.class,
            () -> routing.indexShard(randomAlphaOfLength(5), null, XContentType.JSON, source)
        );
        assertThat(
            e.getMessage(),
            equalTo("Error extracting routing: Failed to parse object: expecting value token but found [START_OBJECT]")
        );
    }

    public void testRoutingPathBwc() throws IOException {
        IndexVersion version = IndexVersionUtils.randomCompatibleVersion(random());
        IndexRouting routing = indexRoutingForPath(version, 8, "dim.*,other.*,top");
        /*
         * These when we first added routing_path. If these values change
         * time series will be routed to unexpected shards. You may modify
         * them with a new index created version, but when you do you must
         * copy this test and patch the versions at the top. Because newer
         * versions of Elasticsearch must continue to route based on the
         * version on the index.
         */
        assertIndexShard(routing, Map.of("dim", Map.of("a", "a")), 4);
        assertIndexShard(routing, Map.of("dim", Map.of("a", "b")), 5);
        assertIndexShard(routing, Map.of("dim", Map.of("c", "d")), 4);
        assertIndexShard(routing, Map.of("other", Map.of("a", "a")), 7);
        assertIndexShard(routing, Map.of("top", "a"), 5);
        assertIndexShard(routing, Map.of("dim", Map.of("c", "d"), "top", "b"), 0);
        assertIndexShard(routing, Map.of("dim.a", "a"), 4);
    }

    public void testRoutingPathReadWithInvalidString() throws IOException {
        int shards = between(2, 1000);
        IndexRouting indexRouting = indexRoutingForPath(shards, "foo");
        Exception e = expectThrows(ResourceNotFoundException.class, () -> shardIdForReadFromSourceExtracting(indexRouting, "!@#"));
        assertThat(e.getMessage(), equalTo("invalid id [!@#] for index [test] in time series mode"));
    }

    public void testRoutingPathReadWithShortString() throws IOException {
        int shards = between(2, 1000);
        IndexRouting indexRouting = indexRoutingForPath(shards, "foo");
        Exception e = expectThrows(ResourceNotFoundException.class, () -> shardIdForReadFromSourceExtracting(indexRouting, ""));
        assertThat(e.getMessage(), equalTo("invalid id [] for index [test] in time series mode"));
    }

    /**
     * Extract a shardId from an {@link IndexRouting} that extracts routingusing a randomly
     * chosen method. All of the random methods <strong>should</strong> return the
     * same results.
     */
    private int shardIdForReadFromSourceExtracting(IndexRouting indexRouting, String id) {
        return randomBoolean() ? indexRouting.deleteShard(id, null) : indexRouting.getShard(id, null);
    }

    private IndexRouting indexRoutingForPath(int shards, String path) {
        return indexRoutingForPath(IndexVersion.current(), shards, path);
    }

    private IndexRouting indexRoutingForPath(IndexVersion createdVersion, int shards, String path) {
        return IndexRouting.fromIndexMetadata(
            IndexMetadata.builder("test")
                .settings(settings(createdVersion).put(IndexMetadata.INDEX_ROUTING_PATH.getKey(), path))
                .numberOfShards(shards)
                .numberOfReplicas(1)
                .build()
        );
    }

    private void assertIndexShard(IndexRouting routing, Map<String, Object> source, int expectedShard) throws IOException {
        byte[] suffix = randomSuffix();
        BytesReference sourceBytes = source(source);
        assertThat(routing.indexShard(randomAlphaOfLength(5), null, XContentType.JSON, sourceBytes), equalTo(expectedShard));
        IndexRouting.ExtractFromSource r = (IndexRouting.ExtractFromSource) routing;
        String idFromSource = r.createId(XContentType.JSON, sourceBytes, suffix);
        assertThat(shardIdForReadFromSourceExtracting(routing, idFromSource), equalTo(expectedShard));
        Map<String, Object> flattened = flatten(source);
        String idFromFlattened = r.createId(flattened, suffix);
        assertThat(idFromFlattened, equalTo(idFromSource));

        IndexRouting.ExtractFromSource.Builder b = r.builder();
        for (Map.Entry<String, Object> e : flattened.entrySet()) {
            if (e.getValue() instanceof List<?> listValue) {
                listValue.forEach(v -> b.addMatching(e.getKey(), new BytesRef(v.toString())));
            } else {
                b.addMatching(e.getKey(), new BytesRef(e.getValue().toString()));
            }
        }
        String idFromBuilder = b.createId(suffix, () -> { throw new AssertionError(); });
        assertThat(idFromBuilder, equalTo(idFromSource));
    }

    private byte[] randomSuffix() {
        byte[] suffix = new byte[between(0, 10)];
        for (int i = 0; i < suffix.length; i++) {
            suffix[i] = randomByte();
        }
        return suffix;
    }

    private BytesReference source(Map<String, Object> doc) throws IOException {
        return BytesReference.bytes(
            JsonXContent.contentBuilder()
                .copyCurrentStructure(
                    new MapXContentParser(
                        NamedXContentRegistry.EMPTY,
                        DeprecationHandler.IGNORE_DEPRECATIONS,
                        doc,
                        randomFrom(XContentType.values())
                    )
                )
        );
    }

    private Map<String, Object> flatten(Map<String, Object> m) {
        Map<String, Object> result = new HashMap<>();
        flatten(result, null, m);
        return result;
    }

    private void flatten(Map<String, Object> result, String path, Map<?, ?> m) {
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String subPath = path == null ? e.getKey().toString() : path + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> subM) {
                flatten(result, subPath, subM);
            } else {
                result.put(subPath, e.getValue());
            }
        }
    }

    /**
     * Build the hash we expect from the extracter.
     */
    private int hash(List<String> keysAndValues) {
        assertThat(keysAndValues.size() % 2, equalTo(0));
        int hash = 0;
        for (int i = 0; i < keysAndValues.size(); i += 2) {
            int keyHash = StringHelper.murmurhash3_x86_32(new BytesRef(keysAndValues.get(i)), 0);
            int valueHash = StringHelper.murmurhash3_x86_32(new BytesRef(keysAndValues.get(i + 1)), 0);
            hash = hash * 31 + (keyHash ^ valueHash);
        }
        return hash;
    }
}
