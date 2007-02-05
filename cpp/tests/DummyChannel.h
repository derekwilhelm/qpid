#ifndef _tests_DummyChannel_h
#define _tests_DummyChannel_h

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

#include <boost/shared_ptr.hpp>
#include "framing/MethodContext.h"
#include "framing/ChannelAdapter.h"
#include "framing/OutputHandler.h"
#include "framing/AMQFrame.h"
#include "BasicGetBody.h"

/** Dummy output handler to collect frames */
struct DummyOutputHandler : public qpid::framing::OutputHandler {
    std::vector<qpid::framing::AMQFrame*> frames;
    void send(qpid::framing::AMQFrame* frame){ frames.push_back(frame); }
};

/**
 * Combination dummy OutputHandler and ChannelAdapter for tests.
 */
struct DummyChannel : public qpid::framing::ChannelAdapter
{
    typedef qpid::framing::BasicGetBody Body;
    static Body::shared_ptr basicGetBody() {
        return Body::shared_ptr(
            new Body(qpid::framing::ProtocolVersion()));
    }

    DummyOutputHandler out;

    DummyChannel(qpid::framing::ChannelId id) {
        init(id, out, qpid::framing::ProtocolVersion());
    }

    bool isOpen() const { return true; }

    void handleHeader(
        boost::shared_ptr<qpid::framing::AMQHeaderBody> b) { send(b); }
    void handleContent(
        boost::shared_ptr<qpid::framing::AMQContentBody> b) { send(b); }
    void handleHeartbeat(
        boost::shared_ptr<qpid::framing::AMQHeartbeatBody> b) { send(b); }
    void handleMethodInContext(
        boost::shared_ptr<qpid::framing::AMQMethodBody> method,
        const qpid::framing::MethodContext& context)
    {
        context.channel->send(method);
    };

};

#endif  /*!_tests_DummyChannel_h*/
