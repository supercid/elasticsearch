/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.aggs.frequentitemsets;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.MockPageCacheRecycler;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.ml.aggs.frequentitemsets.mr.ItemSetMapReduceValueSource.Field;
import org.junit.After;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import static org.elasticsearch.core.Tuple.tuple;
import static org.elasticsearch.xpack.ml.aggs.frequentitemsets.mr.ItemSetMapReduceValueSourceTests.createKeywordFieldTestInstance;

public class ItemSetTraverserTests extends ESTestCase {

    static BigArrays mockBigArrays() {
        return new MockBigArrays(new MockPageCacheRecycler(Settings.EMPTY), new NoneCircuitBreakerService());
    }

    private HashBasedTransactionStore transactionStore = null;
    private ItemSetTraverser it = null;

    @After
    public void closeReleasables() throws IOException {
        Releasables.close(transactionStore, it);
    }

    public void testIteration() throws IOException {
        transactionStore = new HashBasedTransactionStore(mockBigArrays());
        Field field = createKeywordFieldTestInstance("field", 0);

        // create some transactions, for simplicity all with the same key
        transactionStore.add(
            Stream.of(
                tuple(field, List.of("a", "d", "f")),
                tuple(field, List.of("a", "c", "d", "e")),
                tuple(field, List.of("b", "d")),
                tuple(field, List.of("b", "c", "d")),
                tuple(field, List.of("b", "c")),
                tuple(field, List.of("a", "b", "d")),
                tuple(field, List.of("b", "d", "e")),
                tuple(field, List.of("b", "c", "e", "g")),
                tuple(field, List.of("c", "d", "f")),
                tuple(field, List.of("a", "b", "d"))
            )
        );

        // we don't want to prune
        transactionStore.prune(0.1);
        it = new ItemSetTraverser(transactionStore.getTopItemIds());

        /**
         * items are sorted by frequency:
         * d:8, b:7, c:5, a:4, e:3, f:2, g:1
         * this creates the following traversal tree:
         *
         * 1: d-->b-->c-->a-->e-->f-->g
         * 2:         |   |    `->g
         * 3:         |   |`->f-->g
         * 4:         |    `->g
         * 5:         |`->e-->f-->g
         * 6:         |    `->g
         * 7:         |`->f-->g
         * 8:          `->g
         * ...
         */

        assertTrue(it.next());
        assertEquals("d", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(1, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("b", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(2, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("c", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(3, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("a", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(4, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("e", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(5, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("f", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(6, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("g", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(7, it.getNumberOfItems());

        // branch row 2
        assertTrue(it.next());
        assertEquals("g", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(6, it.getNumberOfItems());

        // branch row 3
        assertTrue(it.next());
        assertEquals("f", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(5, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("g", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(6, it.getNumberOfItems());

        // branch row 4
        assertTrue(it.next());
        assertEquals("g", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(5, it.getNumberOfItems());

        // branch row 5
        assertTrue(it.next());
        assertEquals("e", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(4, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("f", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(5, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("g", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(6, it.getNumberOfItems());

        // branch row 6
        assertTrue(it.next());
        assertEquals("g", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(5, it.getNumberOfItems());

        // branch row 7
        assertTrue(it.next());
        assertEquals("f", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(4, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("g", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(5, it.getNumberOfItems());

        // branch row 8
        assertTrue(it.next());
        assertEquals("g", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(4, it.getNumberOfItems());

        int furtherSteps = 0;
        while (it.next()) {
            ++furtherSteps;
        }

        assertEquals(109, furtherSteps);
    }

    public void testPruning() throws IOException {
        transactionStore = new HashBasedTransactionStore(mockBigArrays());
        Field field = createKeywordFieldTestInstance("field", 0);

        // create some transactions, for simplicity all with the same key
        transactionStore.add(
            Stream.of(
                tuple(field, List.of("a", "d", "f")),
                tuple(field, List.of("a", "c", "d", "e")),
                tuple(field, List.of("b", "d")),
                tuple(field, List.of("b", "c", "d")),
                tuple(field, List.of("b", "c")),
                tuple(field, List.of("a", "b", "d")),
                tuple(field, List.of("b", "d", "e")),
                tuple(field, List.of("b", "c", "e", "g")),
                tuple(field, List.of("c", "d", "f")),
                tuple(field, List.of("a", "b", "d"))
            )
        );

        // we don't want to prune
        transactionStore.prune(0.1);
        it = new ItemSetTraverser(transactionStore.getTopItemIds());

        /**
         * items are sorted by frequency:
         * d:8, b:7, c:5, a:4, e:3, f:2, g:1
         * this creates the following traversal tree:
         *
         * this item we prune the tree in various places marked with "[", "]"
         *
         * 1: d-->b-->c-->a-->e[-->f-->g    ]
         * 2:         |   |    [`->g        ]
         * 3:         |   |`->f-->g
         * 4:         |    `->g
         * 5:         |`->e-->f-->g
         * 6:         |    `->g
         * 7:         |`->f-->g
         * 8:          `->g
         * ...
         */

        assertTrue(it.next());
        assertEquals("d", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(1, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("b", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(2, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("c", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(3, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("a", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(4, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("e", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(5, it.getNumberOfItems());

        // now prune the tree
        it.prune();

        // branch row 3
        assertTrue(it.next());
        assertEquals("f", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(5, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("g", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(6, it.getNumberOfItems());

        // prune, which actually is ineffective, as we would go up anyway
        it.prune();

        // branch row 4
        assertTrue(it.next());
        assertEquals("g", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(5, it.getNumberOfItems());

        // branch row 5
        assertTrue(it.next());
        assertEquals("e", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(4, it.getNumberOfItems());

        // prune
        it.prune();

        // branch row 7
        assertTrue(it.next());
        assertEquals("f", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(4, it.getNumberOfItems());
        assertTrue(it.next());
        assertEquals("g", transactionStore.getItem(it.getItemId()).v2());
        assertEquals(5, it.getNumberOfItems());

        // prune aggressively
        it.prune();
        it.prune();
        it.prune();
        it.prune();
        it.prune();
        it.prune();
        it.prune();

        int furtherSteps = 0;
        while (it.next()) {
            ++furtherSteps;
        }

        assertEquals(0, furtherSteps);
    }

}
