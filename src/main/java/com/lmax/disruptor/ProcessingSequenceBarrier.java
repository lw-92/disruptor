/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;


/**
 * {@link SequenceBarrier} handed out for gating {@link EventProcessor}s on a cursor sequence and optional dependent {@link EventProcessor}(s),
 * using the given WaitStrategy.
 */
final class ProcessingSequenceBarrier implements SequenceBarrier {
    private final WaitStrategy waitStrategy;
    //当消费者之前没有依赖关系的时候，那么dependentSequence=cursorSequence
    //存在依赖关系的时候，dependentSequence 里存放的是一组依赖的Sequence，get方法得到的是最小的序列值
    //所谓的依赖关系是有两个消费者A、B，其中B需要在A之后进行消费，这A的序列就是B需要依赖的序列，因为B的消费速度不能超过A
    private final Sequence dependentSequence;
    private volatile boolean alerted = false;
    /**
     * cursorSequence 代表的是写指针。代表事件发布者发布到那个位置
     */
    private final Sequence cursorSequence;
    /**
     * sequencer=SingleProducerSequencer or MultiProducerSequencer的引用
     */
    private final Sequencer sequencer;

    ProcessingSequenceBarrier(
            final Sequencer sequencer,
            final WaitStrategy waitStrategy,
            final Sequence cursorSequence,
            final Sequence[] dependentSequences) {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        this.cursorSequence = cursorSequence;
        if (0 == dependentSequences.length) {
            dependentSequence = cursorSequence;
        } else {
            dependentSequence = new FixedSequenceGroup(dependentSequences);
        }
    }

    @Override
    public long waitFor(final long sequence)
            throws AlertException, InterruptedException, TimeoutException {
        checkAlert();
        ////调用消费者的waitStrategy来等待sequence变得可用，这里会等待依赖的序列能处理完毕之后再处理
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);
          //判断申请的序列和可用的序列大小
        if (availableSequence < sequence) {
            return availableSequence;
        }
        //如果是单线程生产者直接返回availableSequence
        //多线程生产者判断是否可用，不可用返回sequence-1
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);
    }

    @Override
    public long getCursor() {
        return dependentSequence.get();
    }

    @Override
    public boolean isAlerted() {
        return alerted;
    }

    @Override
    public void alert() {
        alerted = true;
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void clearAlert() {
        alerted = false;
    }

    @Override
    public void checkAlert() throws AlertException {
        if (alerted) {
            throw AlertException.INSTANCE;
        }
    }
}