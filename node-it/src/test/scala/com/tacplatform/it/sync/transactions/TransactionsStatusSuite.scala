package com.tacplatform.it.sync.transactions

import com.tacplatform.api.http.ApiError.InvalidIds
import com.tacplatform.common.state.ByteStr
import com.tacplatform.common.utils.EitherExt2
import com.tacplatform.it.NTPTime
import com.tacplatform.it.api.SyncHttpApi._
import com.tacplatform.it.api.{TransactionInfo, TransactionStatus}
import com.tacplatform.it.sync._
import com.tacplatform.it.transactions.BaseTransactionSuite
import com.tacplatform.transaction.Asset.Tac
import com.tacplatform.transaction.ProvenTransaction
import com.tacplatform.transaction.transfer.TransferTransaction
import play.api.libs.json._

import scala.util.Random

class TransactionsStatusSuite extends BaseTransactionSuite with NTPTime {

  import TransactionsStatusSuite._

  test("/transactions/status should return correct data") {

    val txs = mkTransactions

    val confirmedTxs   = txs.slice(0, 10)
    val unconfirmedTxs = txs.slice(10, 15)
    val notFoundTxs    = txs.slice(15, 20)
    val txIds          = txs.map(_.id().toString)

    confirmedTxs.foreach(tx => notMiner.postJson("/transactions/broadcast", tx.json()))

    val confirmedTxsInfo = waitForTransactions(confirmedTxs)

    nodes.waitForHeightArise()

    docker.stopContainer(dockerNodes().head)

    unconfirmedTxs.foreach(tx => notMiner.postJson("/transactions/broadcast", tx.json()))

    notMiner.utxSize shouldBe 5

    val checkData = CheckData(notMiner.height, confirmedTxsInfo, unconfirmedTxs.map(_.id().toString), notFoundTxs.map(_.id().toString))

    val postJsonResult = notMiner.transactionStatus(txIds)
    val postFormResult =
      Json.parse(notMiner.postForm("/transactions/status", txIds.map(("id", _)): _*).getResponseBody).as[List[TransactionStatus]]
    val getResult =
      Json.parse(notMiner.get(s"/transactions/status?${txIds.map(id => s"id=$id").mkString("&")}").getResponseBody).as[List[TransactionStatus]]

    check(checkData, postJsonResult)
    check(checkData, postFormResult)
    check(checkData, getResult)

    val maxTxList = (1 to 1000).map(_ => txIds.head).toList
    val result    = notMiner.transactionStatus(maxTxList)
    result.size shouldBe maxTxList.size
    result.forall(_ == result.head)

    assertBadRequestAndMessage(notMiner.transactionStatus(maxTxList :+ txIds.head), "Too big sequence requested")
    assertBadRequestAndMessage(notMiner.transactionStatus(Seq()), "Empty request")

    assertApiError(notMiner.transactionStatus(Random.shuffle(txIds :+ "illegal id")), InvalidIds(Seq("illegal id")))
  }

  private def check(data: CheckData, result: Seq[TransactionStatus]): Unit = {
    result.size shouldBe data.size

    val confirmed   = result.filter(_.status == "confirmed")
    val unconfirmed = result.filter(_.status == "unconfirmed")
    val notFound    = result.filter(_.status == "not_found")

    confirmed should contain theSameElementsAs data.confirmed
    unconfirmed should contain theSameElementsAs data.unconfirmed
    notFound should contain theSameElementsAs data.notFound
  }

  private def mkTransactions: List[ProvenTransaction] =
    (1001 to 1020).map { amount =>
      TransferTransaction
        .selfSigned(
          2.toByte,
          miner.keyPair,
          secondKeyPair.toAddress,
          Tac,
          amount,
          Tac,
          minFee,
          ByteStr.empty,
          ntpTime.correctedTime()
        )
        .explicitGet()
    }.toList

  private def waitForTransactions(txs: List[ProvenTransaction]): List[TransactionInfo] =
    txs.map(tx => nodes.waitForTransaction(tx.id().toString))
}

object TransactionsStatusSuite {
  case class CheckData(
      confirmed: List[TransactionStatus],
      unconfirmed: List[TransactionStatus],
      notFound: List[TransactionStatus]
  ) {
    val size: Int = confirmed.size + unconfirmed.size + notFound.size
  }

  object CheckData {
    def apply(height: Int, confirmed: List[TransactionInfo], unconfirmed: List[String], notFound: List[String]): CheckData =
      new CheckData(
        confirmed.map(info => TransactionStatus(info.id, "confirmed", Some(height - info.height), Some(info.height), Some("succeeded"))),
        unconfirmed.map(d => TransactionStatus(d, "unconfirmed", None, None, None)),
        notFound.map(d => TransactionStatus(d, "not_found", None, None, None))
      )
  }
}
