package org.apache.commons.collections4;
import java.util.Queue;
public class QueueUtils { public static <E> Queue<E> synchronizedQueue(Queue<E> q) { return q; } }
