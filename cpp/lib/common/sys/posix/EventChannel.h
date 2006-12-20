#ifndef _sys_EventChannel_h
#define _sys_EventChannel_h

/*
 *
 * Copyright (c) 2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

#include "SharedObject.h"
#include "Exception.h"
#include "sys/Monitor.h"
#include "sys/Time.h"

#include <boost/function.hpp>
#include <memory>

namespace qpid {
namespace sys {

class Event;

/**
 * Channel to post and wait for events.
 */
class EventChannel : public qpid::SharedObject<EventChannel>
{
  public:
    static shared_ptr create();

    /** Exception throw from wait() if channel is shut down. */
    class ShutdownException : public qpid::Exception {};

    ~EventChannel();
    
    /** Post an event to the channel. */
    void post(Event& event);

    /** Post an event to the channel. Must not be 0. */
    void post(Event* event);
        
    /**
     * Wait for the next complete event, up to timeout.
     *@return Pointer to event or 0 if timeout elapses.
     *@exception ShutdownException if the channel is shut down.
     */
    Event* wait(Time timeout = TIME_INFINITE);

    /**
     * Shut down the event channel.
     * Blocks till all threads have exited wait()
     */
    void shutdown();


    // Internal classes.
    class Impl;
    class Queue;
    class Descriptor;
    
  private:

    EventChannel();

    Mutex lock;
    boost::shared_ptr<Impl> impl;
};

/**
 * Base class for all Events.
 * 
 * Derived classes define events representing various async IO operations.
 * When an event is complete, it is returned by the EventChannel to
 * a thread calling wait. The thread will call Event::dispatch() to
 * execute code associated with event completion.
 */
class Event
{
  public:
    /** Type for callback when event is dispatched */
    typedef boost::function0<void> Callback;

    virtual ~Event();

    /** Call the callback provided to the constructor, if any. */
    void dispatch();

    /**
     *If there was an exception processing this Event, return it.
     *@return 0 if there was no exception. 
     */
    qpid::Exception::shared_ptr_const getException() const;

    /** If getException() throw the corresponding exception. */
    void throwIfException();

    /** Set the dispatch callback. */
    void setCallback(Callback cb) { callback = cb; }

    /** Set the exception. */
    void setException(const std::exception& e);

  protected:
    Event(Callback cb=0) : callback(cb) {}

    virtual void prepare(EventChannel::Impl&) = 0;
    virtual void complete(EventChannel::Descriptor&) = 0;

    Callback callback;
    Exception::shared_ptr_const exception;

  friend class EventChannel;
  friend class EventChannel::Queue;
};

/**
 * An event that does not wait for anything, it is processed
 * immediately by one of the channel threads.
 */
class DispatchEvent : public Event {
  public:
    DispatchEvent(Callback cb=0) : Event(cb) {}

  protected:
    void prepare(EventChannel::Impl&);
    void complete(EventChannel::Descriptor&);
};

// Utility base class.
class FDEvent : public Event {
  public:
    int getDescriptor() const { return descriptor; }

  protected:
    FDEvent(Callback cb = 0, int fd = 0)
        : Event(cb), descriptor(fd) {}
    int descriptor;
};

// Utility base class
class IOEvent : public FDEvent {
  public:
    size_t getSize() const { return size; }
    
  protected:
    IOEvent(Callback cb, int fd, size_t sz, bool noWait_) :
        FDEvent(cb, fd), size(sz), noWait(noWait_) {}

    size_t size;
    bool noWait;
};

/** Asynchronous read event */
class ReadEvent : public IOEvent
{
  public:
    explicit ReadEvent(
        int fd=-1, void* buf=0, size_t sz=0,
        Callback cb=0, bool noWait=false
    ) : IOEvent(cb, fd, sz, noWait), buffer(buf), bytesRead(0) {}

    void* getBuffer() const { return buffer; }
    size_t getBytesRead() const { return bytesRead; }
    
  private:
    void prepare(EventChannel::Impl&);
    void complete(EventChannel::Descriptor&);
    ssize_t doRead();

    void* buffer;
    size_t bytesRead;
};

/** Asynchronous write event */
class WriteEvent : public IOEvent
{
  public:
    explicit WriteEvent(int fd=-1, const void* buf=0, size_t sz=0,
                        Callback cb=0) :
        IOEvent(cb, fd, sz, noWait), buffer(buf), bytesWritten(0) {}

    const void* getBuffer() const { return buffer; }
    size_t getBytesWritten() const { return bytesWritten; }

  private:
    void prepare(EventChannel::Impl&);
    void complete(EventChannel::Descriptor&);
    ssize_t doWrite();

    const void* buffer;
    size_t bytesWritten;
};


/** Asynchronous socket accept event */
class AcceptEvent : public FDEvent
{
  public:
    /** Accept a connection on fd. */
    explicit AcceptEvent(int fd=-1, Callback cb=0) :
        FDEvent(cb, fd), accepted(0) {}
    
    /** Get descriptor for accepted server socket */
    int getAcceptedDesscriptor() const { return accepted; }

  private:
    void prepare(EventChannel::Impl&);
    void complete(EventChannel::Descriptor&);

    int accepted;
};


}}



#endif  /*!_sys_EventChannel_h*/
