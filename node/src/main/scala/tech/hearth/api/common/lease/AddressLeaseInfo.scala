package tech.hearth.api.common.lease

import tech.hearth.account.Address
import tech.hearth.api.common.LeaseInfo
import tech.hearth.common.state.ByteStr
import tech.hearth.database.{AddressId, DBExt, DBResource, Keys, RDB}
import tech.hearth.state.{LeaseDetails, StateSnapshot}
import monix.eval.Task
import monix.reactive.Observable

import scala.jdk.CollectionConverters.IteratorHasAsScala

object AddressLeaseInfo {
  def activeLeases(
      rdb: RDB,
      snapshot: StateSnapshot,
      subject: Address
  ): Observable[LeaseInfo] = {
    val snapshotLeases = leasesFromSnapshot(snapshot, subject)
    val dbLeases       = leasesFromDb(rdb, subject)
    Observable.fromIterable(snapshotLeases) ++ dbLeases.filterNot(info => snapshot.cancelledLeases.contains(info.id))
  }

  private def leasesFromSnapshot(snapshot: StateSnapshot, subject: Address): Seq[LeaseInfo] =
    snapshot.newLeases.collect {
      case (id, leaseStatic)
          if !snapshot.cancelledLeases.contains(id) &&
            (subject == leaseStatic.sender.toAddress || subject == leaseStatic.recipientAddress) =>
        LeaseInfo(
          id,
          leaseStatic.sourceId,
          leaseStatic.sender.toAddress,
          leaseStatic.recipientAddress,
          leaseStatic.amount.value,
          leaseStatic.height,
          LeaseInfo.Status.Active
        )
    }.toSeq

  private def leasesFromDb(rdb: RDB, subject: Address): Observable[LeaseInfo] =
    for {
      dbResource <- rdb.db.resourceObservable(rdb.apiHandle.handle)
      (leaseId, details) <- dbResource
        .get(Keys.addressId(subject))
        .map(fromLeaseDbIterator(dbResource, rdb.apiHandle, _))
        .getOrElse(Observable.empty)
    } yield LeaseInfo.fromLeaseDetails(leaseId, details)

  private def fromLeaseDbIterator(dbResource: DBResource, apiHandle: RDB.ApiHandle, addressId: AddressId): Observable[(ByteStr, LeaseDetails)] =
    Observable
      .fromIterator(Task(new LeaseByAddressIterator(dbResource, apiHandle, addressId).asScala))
      .concatMapIterable(identity)
}
