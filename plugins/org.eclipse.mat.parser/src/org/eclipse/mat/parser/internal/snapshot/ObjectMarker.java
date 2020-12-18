/*******************************************************************************
 * Copyright (c) 2008, 2020 SAP AG and IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *    Andrew Johnson (IBM Corporation) - improved multithreading using local stacks
 *******************************************************************************/
package org.eclipse.mat.parser.internal.snapshot;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.Platform;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.BitField;
import org.eclipse.mat.collect.QueueInt;
import org.eclipse.mat.parser.index.IIndexReader;
import org.eclipse.mat.parser.internal.Messages;
import org.eclipse.mat.parser.internal.ParserPlugin;
import org.eclipse.mat.parser.internal.util.IntStack;
import org.eclipse.mat.snapshot.ExcludedReferencesDescriptor;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.NamedReference;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.MessageUtil;
import org.eclipse.mat.util.IProgressListener.Severity;

public class ObjectMarker
{
    int[] roots;
    boolean[] bits;
    IIndexReader.IOne2ManyIndex outbound;
    IProgressListener progressListener;

    final int LEVELS_RUN_INLINE = 4;

    public ObjectMarker(int[] roots, boolean[] bits, IIndexReader.IOne2ManyIndex outbound,
                    IProgressListener progressListener)
    {
        this(roots, bits, outbound, 0, progressListener);
    }

    public ObjectMarker(int[] roots, boolean[] bits, IIndexReader.IOne2ManyIndex outbound, long outboundLength,
                    IProgressListener progressListener)
    {
        this.roots = roots;
        this.bits = bits;
        this.outbound = outbound;
        this.progressListener = progressListener;
    }

    public class FjObjectMarker extends RecursiveAction
    {
        final int[] roots;
        final boolean[] visited;
        final IIndexReader.IOne2ManyIndex outbound;
        final boolean topLevel;

        private FjObjectMarker(final int[] roots, final boolean[] visited, final IIndexReader.IOne2ManyIndex outbound,
                        final boolean topLevel)
        {
            this.roots = roots;
            this.visited = visited;
            this.outbound = outbound;
            this.topLevel = topLevel;
        }

        public void compute()
        {
            if (topLevel)
            {
                // report progress only for top level tasks
                progressListener.beginTask(Messages.ObjectMarker_MarkingObjects, roots.length);
                compute(roots, LEVELS_RUN_INLINE);
                progressListener.done();
            } else {
                compute(roots, LEVELS_RUN_INLINE);
            }
        }

        void compute(final int[] currentRoots, final int levelsLeft)
        {
            for (int r : currentRoots)
            {
                // mark away
                if (!visited[r])
                {
                    visited[r] = true;
                    int[] nextLevel = outbound.get(r);
                    if (levelsLeft == 0)
                    {
                        new FjObjectMarker(nextLevel, visited, outbound, false).fork();
                    }
                    else
                    {
                        compute(nextLevel, levelsLeft - 1);
                    }
                }

                // update UI, check for stop
                if (topLevel)
                {
                    progressListener.worked(1);
                    if (progressListener.isCanceled())
                    { return; }
                }
            }
        }
    }

    public int markSingleThreaded()
    {
        int before = countMarked();
        try
        {
            markMultiThreaded(1);
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
        int after = countMarked();
        return after - before;
    }

    public void markMultiThreaded(int threads) throws InterruptedException
    {
        // to control number of threads, and cleanly wait for comlpetion, create our own pool
        // in theory this should automatically determined by FJ commonPool, however that
        // would break the interface specifying 'threads'
        ForkJoinPool pool = new ForkJoinPool(threads);
        pool.execute(new FjObjectMarker(roots, bits, outbound, true));
        pool.shutdown();
        while (!pool.awaitTermination(1000, TimeUnit.MILLISECONDS))
        {
            // being stuck here would be a bug; tasks are not ending
            // TODO is there a heuristic that can be used to flag if no progress is made?
        }
    }

    int countMarked()
    {
        int marked = 0;
        for (boolean b : bits)
            if (b)
                marked++;
        return marked;
    }

    public int markSingleThreaded(ExcludedReferencesDescriptor[] excludeSets, ISnapshot snapshot)
                    throws SnapshotException, IProgressListener.OperationCanceledException
    {
        /*
         * prepare the exclude stuff
         */
        BitField excludeObjectsBF = new BitField(snapshot.getSnapshotInfo().getNumberOfObjects());
        for (ExcludedReferencesDescriptor set : excludeSets)
        {
            for (int k : set.getObjectIds())
            {
                excludeObjectsBF.set(k);
            }
        }

        int count = 0; // # of processed objects in the stack
        int rootsToProcess = 0; // counter to report progress

        /* a stack of int structure */
        int size = 0; // # of elements in the stack
        int[] data = new int[10 * 1024]; // data for the stack - start with 10k

        /* first put all "roots" in the stack, and mark them as processed */
        for (int rootId : roots)
        {
            if (!bits[rootId])
            {
                /* start stack.push() */
                if (size == data.length)
                {
                    int[] newArr = new int[data.length << 1];
                    System.arraycopy(data, 0, newArr, 0, data.length);
                    data = newArr;
                }
                data[size++] = rootId;
                /* end stack.push() */

                bits[rootId] = true; // mark the object
                count++;

                rootsToProcess++;
            }
        }

        /* now do the real marking */
        progressListener.beginTask(Messages.ObjectMarker_MarkingObjects, rootsToProcess);

        int current;

        while (size > 0) // loop until there are elements in the stack
        {
            /* do a stack.pop() */
            current = data[--size];

            /* report progress if one of the roots is processed */
            if (size <= rootsToProcess)
            {
                rootsToProcess--;
                progressListener.worked(1);
                if (progressListener.isCanceled())
                    throw new IProgressListener.OperationCanceledException();
            }

            for (int child : outbound.get(current))
            {
                if (!bits[child]) // already visited?
                {
                    if (!refersOnlyThroughExcluded(current, child, excludeSets, excludeObjectsBF, snapshot))
                    {
                        /* start stack.push() */
                        if (size == data.length)
                        {
                            int[] newArr = new int[data.length << 1];
                            System.arraycopy(data, 0, newArr, 0, data.length);
                            data = newArr;
                        }
                        data[size++] = child;
                        /* end stack.push() */

                        bits[child] = true; // mark the object
                        count++;
                    }
                }
            }
        }

        progressListener.done();

        return count;
    }

    private boolean refersOnlyThroughExcluded(int referrerId, int referentId,
                    ExcludedReferencesDescriptor[] excludeSets, BitField excludeObjectsBF, ISnapshot snapshot)
                    throws SnapshotException
    {
        if (!excludeObjectsBF.get(referrerId))
            return false;

        Set<String> excludeFields = null;
        for (ExcludedReferencesDescriptor set : excludeSets)
        {
            if (set.contains(referrerId))
            {
                excludeFields = set.getFields();
                break;
            }
        }
        if (excludeFields == null)
            return true; // treat null as all fields

        IObject referrerObject = snapshot.getObject(referrerId);
        long referentAddr = snapshot.mapIdToAddress(referentId);

        List<NamedReference> refs = referrerObject.getOutboundReferences();
        for (NamedReference reference : refs)
        {
            if (referentAddr == reference.getObjectAddress() && !excludeFields.contains(reference.getName()))
            { return false; }
        }
        return true;
    }

}
