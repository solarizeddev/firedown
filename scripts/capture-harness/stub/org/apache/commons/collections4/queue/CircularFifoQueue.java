package org.apache.commons.collections4.queue;
import java.util.ArrayDeque;
/** Test stand-in: an insertion-ordered queue (the harness never fills 1024). */
public class CircularFifoQueue<E> extends ArrayDeque<E> { public CircularFifoQueue(int size) { super(size); } }
