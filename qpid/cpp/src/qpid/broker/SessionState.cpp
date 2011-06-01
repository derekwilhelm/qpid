/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
#include "qpid/broker/SessionState.h"
#include "qpid/broker/Broker.h"
#include "qpid/broker/ConnectionState.h"
#include "qpid/broker/DeliveryRecord.h"
#include "qpid/broker/SessionManager.h"
#include "qpid/broker/SessionHandler.h"
#include "qpid/broker/RateFlowcontrol.h"
#include "qpid/sys/Timer.h"
#include "qpid/framing/AMQContentBody.h"
#include "qpid/framing/AMQHeaderBody.h"
#include "qpid/framing/AMQMethodBody.h"
#include "qpid/framing/reply_exceptions.h"
#include "qpid/framing/ServerInvoker.h"
#include "qpid/log/Statement.h"
#include "qpid/management/ManagementAgent.h"
#include "qpid/framing/AMQP_ClientProxy.h"

#include <boost/bind.hpp>
#include <boost/lexical_cast.hpp>

namespace qpid {
namespace broker {

using namespace framing;
using sys::Mutex;
using boost::intrusive_ptr;
using qpid::management::ManagementAgent;
using qpid::management::ManagementObject;
using qpid::management::Manageable;
using qpid::management::Args;
using qpid::sys::AbsTime;
//using qpid::sys::Timer;
namespace _qmf = qmf::org::apache::qpid::broker;

SessionState::SessionState(
    Broker& b, SessionHandler& h, const SessionId& id,
    const SessionState::Configuration& config, bool delayManagement)
    : qpid::SessionState(id, config),
      broker(b), handler(&h),
      semanticState(*this, *this),
      adapter(semanticState),
      msgBuilder(&broker.getStore()),
      mgmtObject(0),
      rateFlowcontrol(0),
      asyncCommandManager(new AsyncCommandManager(this))
{
    uint32_t maxRate = broker.getOptions().maxSessionRate;
    if (maxRate) {
        if (handler->getConnection().getClientThrottling()) {
            rateFlowcontrol.reset(new RateFlowcontrol(maxRate));
        } else {
            QPID_LOG(warning, getId() << ": Unable to flow control client - client doesn't support");
        }
    }
    if (!delayManagement) addManagementObject();
    attach(h);
}

void SessionState::addManagementObject() {
    if (GetManagementObject()) return; // Already added.
    Manageable* parent = broker.GetVhostObject ();
    if (parent != 0) {
        ManagementAgent* agent = getBroker().getManagementAgent();
        if (agent != 0) {
            mgmtObject = new _qmf::Session
                (agent, this, parent, getId().getName());
            mgmtObject->set_attached (0);
            mgmtObject->set_detachedLifespan (0);
            mgmtObject->clr_expireTime();
            if (rateFlowcontrol)
                mgmtObject->set_maxClientRate(rateFlowcontrol->getRate());
            agent->addObject(mgmtObject);
        }
    }
}

SessionState::~SessionState() {
    asyncCommandManager->cancel();
    semanticState.closed();
    if (mgmtObject != 0)
        mgmtObject->resourceDestroy ();

    if (flowControlTimer)
        flowControlTimer->cancel();
}

AMQP_ClientProxy& SessionState::getProxy() {
    assert(isAttached());
    return handler->getProxy();
}

uint16_t SessionState::getChannel() const {
    assert(isAttached());
    return handler->getChannel();
}

ConnectionState& SessionState::getConnection() {
    assert(isAttached());
    return handler->getConnection();
}

bool SessionState::isLocal(const ConnectionToken* t) const
{
    return isAttached() && &(handler->getConnection()) == t;
}

void SessionState::detach() {
    QPID_LOG(debug, getId() << ": detached on broker.");
    asyncCommandManager->detached();
    disableOutput();
    handler = 0;
    if (mgmtObject != 0)
        mgmtObject->set_attached  (0);
}

void SessionState::disableOutput()
{
    semanticState.detached(); //prevents further activateOutput calls until reattached
}

void SessionState::attach(SessionHandler& h) {
    QPID_LOG(debug, getId() << ": attached on broker.");
    handler = &h;
    if (mgmtObject != 0)
    {
        mgmtObject->set_attached (1);
        mgmtObject->set_connectionRef (h.getConnection().GetManagementObject()->getObjectId());
        mgmtObject->set_channelId (h.getChannel());
    }
    asyncCommandManager->attached();
}

void SessionState::abort() {
    if (isAttached())
        getConnection().outputTasks.abort();
}

void SessionState::activateOutput() {
    if (isAttached())
        getConnection().outputTasks.activateOutput();
}

void SessionState::giveReadCredit(int32_t credit) {
    if (isAttached())
        getConnection().outputTasks.giveReadCredit(credit);
}

ManagementObject* SessionState::GetManagementObject (void) const
{
    return (ManagementObject*) mgmtObject;
}

Manageable::status_t SessionState::ManagementMethod (uint32_t methodId,
                                                     Args&    /*args*/,
                                                     string&  /*text*/)
{
    Manageable::status_t status = Manageable::STATUS_UNKNOWN_METHOD;

    switch (methodId)
    {
    case _qmf::Session::METHOD_DETACH :
        if (handler != 0) {
            handler->sendDetach();
        }
        status = Manageable::STATUS_OK;
        break;

    case _qmf::Session::METHOD_CLOSE :
        /*
          if (handler != 0)
          {
          handler->getConnection().closeChannel(handler->getChannel());
          }
          status = Manageable::STATUS_OK;
          break;
        */

    case _qmf::Session::METHOD_SOLICITACK :
    case _qmf::Session::METHOD_RESETLIFESPAN :
        status = Manageable::STATUS_NOT_IMPLEMENTED;
        break;
    }

    return status;
}

void SessionState::handleCommand(framing::AMQMethodBody* method, const SequenceNumber& id)
{
    currentCommandComplete = true;      // assumed, can be overridden by invoker method (this sucks).
    syncCurrentCommand = method->isSync();
    acceptRequired = false;
    Invoker::Result invocation = invoke(adapter, *method);
    if (!invocation.wasHandled()) {
        throw NotImplementedException(QPID_MSG("Not implemented: " << *method));
    }

    if (currentCommandComplete) {
        completeCommand(id, invocation, false, syncCurrentCommand);
    }
}


struct ScheduledCreditTask : public sys::TimerTask {
    sys::Timer& timer;
    SessionState& sessionState;
    ScheduledCreditTask(const qpid::sys::Duration& d, sys::Timer& t,
                        SessionState& s) :
        TimerTask(d,"ScheduledCredit"),
        timer(t),
        sessionState(s)
    {}

    void fire() {
        // This is the best we can currently do to avoid a destruction/fire race
        sessionState.getConnection().requestIOProcessing(boost::bind(&ScheduledCreditTask::sendCredit, this));
    }

    void sendCredit() {
        if ( !sessionState.processSendCredit(0) ) {
            QPID_LOG(warning, sessionState.getId() << ": Reschedule sending credit");
            setupNextFire();
            timer.add(this);
        }
    }
};

void SessionState::handleContent(AMQFrame& frame, const SequenceNumber& id)
{
    if (frame.getBof() && frame.getBos()) //start of frameset
        msgBuilder.start(id);
    intrusive_ptr<Message> msg(msgBuilder.getMessage());
    msgBuilder.handle(frame);
    if (frame.getEof() && frame.getEos()) {//end of frameset
        if (frame.getBof()) {
            //i.e this is a just a command frame, add a dummy header
            AMQFrame header((AMQHeaderBody()));
            header.setBof(false);
            header.setEof(false);
            msg->getFrames().append(header);
        }
        msg->setPublisher(&getConnection());
        msg->getIngressCompletion().begin();
        currentCommandComplete = true;      // assumed
        syncCurrentCommand = msg->getFrames().getMethod()->isSync();
        acceptRequired = msg->requiresAccept();
        semanticState.handle(msg);
        msgBuilder.end();
        IncompleteIngressMsgXfer xfer(this, msg);
        msg->getIngressCompletion().end(xfer);  // allows msg to complete xfer
    }

    // Handle producer session flow control
    if (rateFlowcontrol && frame.getBof() && frame.getBos()) {
        if ( !processSendCredit(1) ) {
            QPID_LOG(debug, getId() << ": Schedule sending credit");
            sys::Timer& timer = getBroker().getTimer();
            // Use heuristic for scheduled credit of time for 50 messages, but not longer than 500ms
            sys::Duration d = std::min(sys::TIME_SEC * 50 / rateFlowcontrol->getRate(), 500 * sys::TIME_MSEC);
            flowControlTimer = new ScheduledCreditTask(d, timer, *this);
            timer.add(flowControlTimer);
        }
    }
}

bool SessionState::processSendCredit(uint32_t msgs)
{
    qpid::sys::ScopedLock<Mutex> l(rateLock);
    // Check for violating flow control
    if ( msgs > 0 && rateFlowcontrol->flowStopped() ) {
        QPID_LOG(warning, getId() << ": producer throttling violation");
        // TODO: Probably do message.stop("") first time then disconnect
        // See comment on getClusterOrderProxy() in .h file
        getClusterOrderProxy().getMessage().stop("");
        return true;
    }
    AbsTime now = AbsTime::now();
    uint32_t sendCredit = rateFlowcontrol->receivedMessage(now, msgs);
    if (mgmtObject) mgmtObject->dec_clientCredit(msgs);
    if ( sendCredit>0 ) {
        QPID_LOG(debug, getId() << ": send producer credit " << sendCredit);
        getClusterOrderProxy().getMessage().flow("", 0, sendCredit);
        rateFlowcontrol->sentCredit(now, sendCredit);
        if (mgmtObject) mgmtObject->inc_clientCredit(sendCredit);
        return true;
    } else {
        return !rateFlowcontrol->flowStopped() ;
    }
}

void SessionState::sendAcceptAndCompletion()
{
    if (!accepted.empty()) {
        getProxy().getMessage().accept(accepted);
        accepted.clear();
    }
    sendCompletion();
}

/** Complete a received command */
void SessionState::completeCommand(const SequenceNumber& id,
                                   const framing::Invoker::Result& results,
                                   bool requiresAccept,
                                   bool syncBitSet)
{
    bool callSendCompletion = false;
    receiverCompleted(id);

    if (results.hasResult()) {
        getProxy().getExecution().result(id, results.getResult());
    }

    if (requiresAccept)
        // will cause msg's seq to appear in the next message.accept we send.
        accepted.add(id);

    // Are there any outstanding Execution.Sync commands pending the
    // completion of this msg?  If so, complete them.
    while (!pendingExecutionSyncs.empty() &&
           receiverGetIncomplete().front() >= pendingExecutionSyncs.front()) {
        const SequenceNumber id = pendingExecutionSyncs.front();
        pendingExecutionSyncs.pop();
        QPID_LOG(debug, getId() << ": delayed execution.sync " << id << " is completed.");
        receiverCompleted(id);
        callSendCompletion = true;   // likely peer is pending for this completion.
    }

    // if the sender has requested immediate notification of the completion...
    if (syncBitSet) {
        sendAcceptAndCompletion();
    } else if (callSendCompletion) {
        sendCompletion();
    }
}

void SessionState::handleIn(AMQFrame& frame) {
    SequenceNumber commandId = receiverGetCurrent();
    //TODO: make command handling more uniform, regardless of whether
    //commands carry content.
    AMQMethodBody* m = frame.getMethod();
    if (m == 0 || m->isContentBearing()) {
        handleContent(frame, commandId);
    } else if (frame.getBof() && frame.getEof()) {
        handleCommand(frame.getMethod(), commandId);
    } else {
        throw InternalErrorException("Cannot handle multi-frame command segments yet");
    }
}

void SessionState::handleOut(AMQFrame& frame) {
    assert(handler);
    handler->out(frame);
}

void SessionState::deliver(DeliveryRecord& msg, bool sync)
{
    uint32_t maxFrameSize = getConnection().getFrameMax();
    assert(senderGetCommandPoint().offset == 0);
    SequenceNumber commandId = senderGetCommandPoint().command;
    msg.deliver(getProxy().getHandler(), commandId, maxFrameSize);
    assert(senderGetCommandPoint() == SessionPoint(commandId+1, 0)); // Delivery has moved sendPoint.
    if (sync) {
        AMQP_ClientProxy::Execution& p(getProxy().getExecution());
        Proxy::ScopedSync s(p);
        p.sync();
    }
}

void SessionState::sendCompletion() {
    handler->sendCompletion();
}

void SessionState::senderCompleted(const SequenceSet& commands) {
    qpid::SessionState::senderCompleted(commands);
    semanticState.completed(commands);
}

void SessionState::readyToSend() {
    QPID_LOG(debug, getId() << ": ready to send, activating output.");
    assert(handler);
    semanticState.attached();
    if (rateFlowcontrol) {
        qpid::sys::ScopedLock<Mutex> l(rateLock);
        // Issue initial credit - use a heuristic here issue min of 300 messages or 1 secs worth
        uint32_t credit = std::min(rateFlowcontrol->getRate(), 300U);
        QPID_LOG(debug, getId() << ": Issuing producer message credit " << credit);
        // See comment on getClusterOrderProxy() in .h file
        getClusterOrderProxy().getMessage().setFlowMode("", 0);
        getClusterOrderProxy().getMessage().flow("", 0, credit);
        rateFlowcontrol->sentCredit(AbsTime::now(), credit);
        if (mgmtObject) mgmtObject->inc_clientCredit(credit);
    }
}

Broker& SessionState::getBroker() { return broker; }

// Session resume is not fully implemented so it is useless to set a
// non-0 timeout. Moreover it creates problems in a cluster because
// dead sessions are kept and interfere with failover.
void SessionState::setTimeout(uint32_t) { }

framing::AMQP_ClientProxy& SessionState::getClusterOrderProxy() {
    return handler->getClusterOrderProxy();
}


// Current received command is an execution.sync command.
// Complete this command only when all preceding commands have completed.
// (called via the invoker() in handleCommand() above)
void SessionState::addPendingExecutionSync()
{
    SequenceNumber syncCommandId = receiverGetCurrent();
    if (receiverGetIncomplete().front() < syncCommandId) {
        currentCommandComplete = false;
        pendingExecutionSyncs.push(syncCommandId);
        asyncCommandManager->flushPendingCommands();
        QPID_LOG(debug, getId() << ": delaying completion of execution.sync " << syncCommandId);
    }
}


void SessionState::registerAsyncCommand(boost::intrusive_ptr<AsyncCommandContext>& aCmd)
{
    /** @todo KAG: ensure this is invoked during handleCommand() context! */
    currentCommandComplete = false;
    asyncCommandManager->addPendingCommand( aCmd, receiverGetCurrent(), acceptRequired, syncCurrentCommand );
}


void SessionState::cancelAsyncCommand(boost::intrusive_ptr<AsyncCommandContext>& aCmd)
{
    asyncCommandManager->cancelPendingCommand(aCmd);
}

/** factory for creating a reference-counted IncompleteIngressMsgXfer object
 * which will be attached to a message that will be completed asynchronously.
 */
boost::intrusive_ptr<AsyncCompletion::Callback>
SessionState::IncompleteIngressMsgXfer::clone()
{
    boost::intrusive_ptr<SessionState::IncompleteIngressMsgXfer> cb(new SessionState::IncompleteIngressMsgXfer(session, msg));

    // this routine is *only* invoked when the message needs to be asynchronously completed.  Otherwise, ::completed()
    // will be invoked directly.
    pending = true;
    boost::intrusive_ptr<SessionContext::AsyncCommandContext>ctxt(boost::static_pointer_cast<SessionContext::AsyncCommandContext>(cb));
    session->registerAsyncCommand(ctxt);
    if (ctxt->getSyncBitSet()) {
        // If the client is pending the message.transfer completion, flush now to force immediate write to journal.
        msg->flush();
    }
    return cb;
}


/** Invoked by the asynchronous completer associated with a received
 * msg that is pending Completion.  May be invoked by the IO thread
 * (sync == true), or some external thread (!sync).
 */
void SessionState::IncompleteIngressMsgXfer::completed(bool sync)
{
    if (!sync) {
        /** note well: this path may execute in any thread.  It is safe to access
         * the scheduledCompleterContext, since *this has a shared pointer to it.
         * but not session!
         */
        session = 0;
        QPID_LOG(debug, ": async completion callback scheduled for msg seq=" << getId());
        completed(framing::Invoker::Result());
    } else {
        // this path runs directly from the ac->end() call in handleContent() above,
        // so *session is definately valid.
        if (session->isAttached()) {
            QPID_LOG(debug, ": receive completed for msg seq=" << getId());
            session->completeCommand(getId(), framing::Invoker::Result(), getRequiresAccept(), getSyncBitSet());
        }
        if (pending) {
            boost::intrusive_ptr<AsyncCommandContext> p(this);
            session->cancelAsyncCommand(p);
        }
    }
}


void SessionState::IncompleteIngressMsgXfer::flush()
{
    msg->flush();
}


/** Scheduled from an asynchronous command's completed callback to run on
 * the IO thread.
 */
void SessionState::AsyncCommandManager::schedule(boost::intrusive_ptr<AsyncCommandManager> ctxt)
{
    ctxt->processCompletedCommands();
}


void SessionState::AsyncCommandManager::addPendingCommand(boost::intrusive_ptr<AsyncCommandContext>& cmd,
                                                          framing::SequenceNumber seq,
                                                          bool acceptRequired, bool syncBitSet)
{
    cmd->setId(seq);
    cmd->setRequiresAccept(acceptRequired);
    cmd->setSyncBitSet(syncBitSet);
    cmd->setManager(this);
    qpid::sys::ScopedLock<qpid::sys::Mutex> l(completerLock);
    std::pair<SequenceNumber, boost::intrusive_ptr<AsyncCommandContext> > item(cmd->getId(), cmd);
    bool unique = pendingCommands.insert(item).second;
    if (!unique) assert(false);
}


void SessionState::AsyncCommandManager::cancelPendingCommand(boost::intrusive_ptr<AsyncCommandContext>& cmd)
{
    qpid::sys::ScopedLock<qpid::sys::Mutex> l(completerLock);
    pendingCommands.erase(cmd->getId());
    cmd->setManager(0);
}



/** done when an execution.sync arrives */
void SessionState::AsyncCommandManager::flushPendingCommands()
{
    std::map<SequenceNumber, boost::intrusive_ptr<AsyncCommandContext> > copy;
    {
        qpid::sys::ScopedLock<qpid::sys::Mutex> l(completerLock);
        copy = pendingCommands;
    }
    // drop lock, so it is safe to call "flush()"
    for (std::map<SequenceNumber, boost::intrusive_ptr<AsyncCommandContext> >::iterator i = copy.begin();
         i != copy.end(); ++i) {
        i->second->flush();
    }
}


/** mark a pending command as completed.
 * This method must be thread safe - it may run on any thread.
 */
void SessionState::AsyncCommandManager::completePendingCommand(boost::intrusive_ptr<AsyncCommandContext>& cmd,
                                                               const framing::Invoker::Result& result)
{
    qpid::sys::ScopedLock<qpid::sys::Mutex> l(completerLock);
    if (session && isAttached) {
        CommandInfo status(cmd->getId(),
                           result,
                           cmd->getRequiresAccept(),
                           cmd->getSyncBitSet());
        completedCommands.push_back(status);
        if (completedCommands.size() == 1) {
            session->getConnection().requestIOProcessing(boost::bind(&schedule,
                                                                     session->asyncCommandManager));
        }
    }
    pendingCommands.erase(cmd->getId());
}


/** Cause the session to complete all completed commands.
 * Executes on the IO thread.
 */
void SessionState::AsyncCommandManager::processCompletedCommands()
{
    qpid::sys::ScopedLock<qpid::sys::Mutex> l(completerLock);

    // when session is destroyed, it clears the session pointer via cancel().
    if (session && session->isAttached()) {
        for (std::vector<CommandInfo>::iterator cmd = completedCommands.begin();
             cmd != completedCommands.end(); ++cmd) {
            session->completeCommand(cmd->id,
                                     cmd->results,
                                     cmd->requiresAccept,
                                     cmd->syncBitSet);
        }
    }
    completedCommands.clear();
}


/** cancel any pending calls to scheduleComplete */
void SessionState::AsyncCommandManager::cancel()
{
    qpid::sys::ScopedLock<qpid::sys::Mutex> l(completerLock);
    session = 0;
}


/** inform the completer that the session has attached,
 * allows command completion scheduling from any thread */
void SessionState::AsyncCommandManager::attached()
{
    qpid::sys::ScopedLock<qpid::sys::Mutex> l(completerLock);
    isAttached = true;
}


/** inform the completer that the session has detached,
 * disables command completion scheduling from any thread */
void SessionState::AsyncCommandManager::detached()
{
    qpid::sys::ScopedLock<qpid::sys::Mutex> l(completerLock);
    isAttached = false;
}

}} // namespace qpid::broker
