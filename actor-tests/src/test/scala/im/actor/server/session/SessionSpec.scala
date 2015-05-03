package im.actor.server.session

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

import akka.testkit.TestProbe
import scodec.bits._

import im.actor.api.rpc.auth.{ RequestSendAuthCode, RequestSignOut, RequestSignUp, ResponseAuth, ResponseSendAuthCode }
import im.actor.api.rpc.codecs._
import im.actor.api.rpc.contacts.UpdateContactRegistered
import im.actor.api.rpc.misc.ResponseVoid
import im.actor.api.rpc.peers.UserOutPeer
import im.actor.api.rpc.sequence.RequestSubscribeToOnline
import im.actor.api.rpc.weak.UpdateUserOffline
import im.actor.api.rpc.{ AuthorizedClientData, Request, RpcOk }
import im.actor.server.mtproto.protocol._
import im.actor.server.mtproto.transport._
import im.actor.server.push.{ SeqUpdatesManager, WeakUpdatesManager }

class SessionSpec extends BaseSessionSpec {
  behavior of "Session actor"

  it should "send Drop on message on wrong message box" in sessions().e1
  it should "send NewSession on first HandleMessageBox" in sessions().e2
  it should "reply to RpcRequestBox" in sessions().e3
  it should "handle user authorization" in sessions().e4
  it should "subscribe to sequence updates" in sessions().e5
  it should "subscribe to weak updates" in sessions().e6
  it should "subscribe to presences" in sessions().e7

  case class sessions() {

    import SessionMessage._

    implicit val probe = TestProbe()

    def e1() = {
      val authId = createAuthId()
      val sessionId = Random.nextLong()
      val session = system.actorOf(Session.props(rpcApiService))

      sendEnvelope(authId, sessionId, session, HandleMessageBox(BitVector.empty.toByteArray))

      probe watch session

      probe.expectMsg(Drop(0, 0, "Cannot parse MessageBox"))
      probe.expectTerminated(session)
    }

    def e2() = {
      val authId = createAuthId()
      val sessionId = Random.nextLong()
      val messageId = Random.nextLong()

      val encodedRequest = RequestCodec.encode(Request(RequestSendAuthCode(75553333333L, 1, "apiKey"))).require
      sendMessageBox(authId, sessionId, sessionRegion.ref, messageId, RpcRequestBox(encodedRequest))

      expectNewSession(authId, sessionId, messageId)
      probe.receiveOne(1.second)
      probe.receiveOne(1.second)
      probe.expectNoMsg()
    }

    def e3() = {
      val authId = createAuthId()
      val sessionId = Random.nextLong()
      val messageId = Random.nextLong()

      val encodedRequest = RequestCodec.encode(Request(RequestSendAuthCode(75553333334L, 1, "apiKey"))).require
      sendMessageBox(authId, sessionId, sessionRegion.ref, messageId, RpcRequestBox(encodedRequest))

      expectNewSession(authId, sessionId, messageId)
      expectMessageAck(authId, sessionId, messageId)

      expectRpcResult() should matchPattern {
        case RpcOk(ResponseSendAuthCode(_, false)) ⇒
      }
    }

    def e4() = {
      val authId = createAuthId()
      val sessionId = Random.nextLong()

      val firstMessageId = Random.nextLong()
      val phoneNumber = 75550000000L

      val encodedCodeRequest = RequestCodec.encode(Request(RequestSendAuthCode(phoneNumber, 1, "apiKey"))).require
      sendMessageBox(authId, sessionId, sessionRegion.ref, firstMessageId, RpcRequestBox(encodedCodeRequest))

      expectNewSession(authId, sessionId, firstMessageId)
      expectMessageAck(authId, sessionId, firstMessageId)

      val smsHash = expectRpcResult().asInstanceOf[RpcOk].response.asInstanceOf[ResponseSendAuthCode].smsHash

      val encodedSignUpRequest = RequestCodec.encode(Request(RequestSignUp(
        phoneNumber = phoneNumber,
        smsHash = smsHash,
        smsCode = "0000",
        name = "Wayne Brain",
        deviceHash = Array(4, 5, 6),
        deviceTitle = "Specs virtual device",
        appId = 1,
        appKey = "appKey",
        isSilent = false
      ))).require

      val secondMessageId = Random.nextLong()
      sendMessageBox(authId, sessionId, sessionRegion.ref, secondMessageId, RpcRequestBox(encodedSignUpRequest))

      expectMessageAck(authId, sessionId, secondMessageId)
      expectRpcResult() should matchPattern {
        case RpcOk(ResponseAuth(_, _)) ⇒
      }

      val encodedSignOutRequest = RequestCodec.encode(Request(RequestSignOut)).require

      val thirdMessageId = Random.nextLong()
      sendMessageBox(authId, sessionId, sessionRegion.ref, thirdMessageId, RpcRequestBox(encodedSignOutRequest))

      expectMessageAck(authId, sessionId, thirdMessageId)
      expectRpcResult() should matchPattern {
        case RpcOk(ResponseVoid) ⇒
      }
    }

    def e5() = {
      val authId = createAuthId()
      val sessionId = Random.nextLong()

      val firstMessageId = Random.nextLong()
      val phoneNumber = 75550000000L

      val encodedCodeRequest = RequestCodec.encode(Request(RequestSendAuthCode(phoneNumber, 1, "apiKey"))).require
      sendMessageBox(authId, sessionId, sessionRegion.ref, firstMessageId, RpcRequestBox(encodedCodeRequest))

      expectNewSession(authId, sessionId, firstMessageId)
      expectMessageAck(authId, sessionId, firstMessageId)

      val smsHash = expectRpcResult().asInstanceOf[RpcOk].response.asInstanceOf[ResponseSendAuthCode].smsHash

      val encodedSignUpRequest = RequestCodec.encode(Request(RequestSignUp(
        phoneNumber = phoneNumber,
        smsHash = smsHash,
        smsCode = "0000",
        name = "Wayne Brain",
        deviceHash = Array(5, 5, 6),
        deviceTitle = "Specs virtual device",
        appId = 1,
        appKey = "appKey",
        isSilent = false
      ))).require

      val secondMessageId = Random.nextLong()
      sendMessageBox(authId, sessionId, sessionRegion.ref, secondMessageId, RpcRequestBox(encodedSignUpRequest))

      expectMessageAck(authId, sessionId, secondMessageId)

      val authResult = expectRpcResult()
      authResult should matchPattern {
        case RpcOk(ResponseAuth(_, _)) ⇒
      }

      implicit val clientData = AuthorizedClientData(authId, sessionId, authResult.asInstanceOf[RpcOk].response.asInstanceOf[ResponseAuth].user.id)

      val update = UpdateContactRegistered(1, true, 1L)
      Await.result(db.run(SeqUpdatesManager.broadcastClientUpdate(update, None)), 1.second)

      expectSeqUpdate(authId, sessionId).update should ===(update.toByteArray)
    }

    def e6() = {
      val authId = createAuthId()
      val sessionId = Random.nextLong()

      val firstMessageId = Random.nextLong()
      val phoneNumber = 75550000000L

      val encodedCodeRequest = RequestCodec.encode(Request(RequestSendAuthCode(phoneNumber, 1, "apiKey"))).require
      sendMessageBox(authId, sessionId, sessionRegion.ref, firstMessageId, RpcRequestBox(encodedCodeRequest))

      expectNewSession(authId, sessionId, firstMessageId)
      expectMessageAck(authId, sessionId, firstMessageId)

      val smsHash = expectRpcResult().asInstanceOf[RpcOk].response.asInstanceOf[ResponseSendAuthCode].smsHash

      val encodedSignUpRequest = RequestCodec.encode(Request(RequestSignUp(
        phoneNumber = phoneNumber,
        smsHash = smsHash,
        smsCode = "0000",
        name = "Wayne Brain",
        deviceHash = Array(5, 5, 6),
        deviceTitle = "Specs virtual device",
        appId = 1,
        appKey = "appKey",
        isSilent = false
      ))).require

      val secondMessageId = Random.nextLong()
      sendMessageBox(authId, sessionId, sessionRegion.ref, secondMessageId, RpcRequestBox(encodedSignUpRequest))

      expectMessageAck(authId, sessionId, secondMessageId)

      val authResult = expectRpcResult()
      authResult should matchPattern {
        case RpcOk(ResponseAuth(_, _)) ⇒
      }

      implicit val clientData = AuthorizedClientData(authId, sessionId, authResult.asInstanceOf[RpcOk].response.asInstanceOf[ResponseAuth].user.id)

      val update = UpdateContactRegistered(1, true, 1L)
      Await.result(db.run(WeakUpdatesManager.broadcastUserWeakUpdate(clientData.userId, update)), 1.second)

      expectWeakUpdate(authId, sessionId).update should ===(update.toByteArray)
    }

    def e7() = {
      val authId = createAuthId()
      val sessionId = Random.nextLong()

      val firstMessageId = Random.nextLong()
      val phoneNumber = 75550000000L

      val encodedCodeRequest = RequestCodec.encode(Request(RequestSendAuthCode(phoneNumber, 1, "apiKey"))).require
      sendMessageBox(authId, sessionId, sessionRegion.ref, firstMessageId, RpcRequestBox(encodedCodeRequest))

      expectNewSession(authId, sessionId, firstMessageId)
      expectMessageAck(authId, sessionId, firstMessageId)

      val smsHash = expectRpcResult().asInstanceOf[RpcOk].response.asInstanceOf[ResponseSendAuthCode].smsHash

      {
        val encodedSignUpRequest = RequestCodec.encode(Request(RequestSignUp(
          phoneNumber = phoneNumber,
          smsHash = smsHash,
          smsCode = "0000",
          name = "Wayne Brain",
          deviceHash = Array(5, 5, 6),
          deviceTitle = "Specs virtual device",
          appId = 1,
          appKey = "appKey",
          isSilent = false
        ))).require

        val messageId = Random.nextLong()
        sendMessageBox(authId, sessionId, sessionRegion.ref, messageId, RpcRequestBox(encodedSignUpRequest))

        expectMessageAck(authId, sessionId, messageId)

        val authResult = expectRpcResult()
        authResult should matchPattern {
          case RpcOk(ResponseAuth(_, _)) ⇒
        }
      }

      {
        val userForSubscribe = 2

        // FIXME: real user and real accessHash
        val encodedSubscribeRequest = RequestCodec.encode(Request(RequestSubscribeToOnline(Vector(UserOutPeer(userForSubscribe, 0L))))).require

        val messageId = Random.nextLong()
        sendMessageBox(authId, sessionId, sessionRegion.ref, messageId, RpcRequestBox(encodedSubscribeRequest))

        expectMessageAck(authId, sessionId, messageId)

        val subscribeResult = expectRpcResult()
        subscribeResult should matchPattern {
          case RpcOk(ResponseVoid) ⇒
        }
      }

      val ub = expectWeakUpdate(authId, sessionId)

      ub.updateHeader should ===(UpdateUserOffline.header)
    }
  }
}
