package com.applaudostudios.store.domain.categories

import akka.pattern.ask

import scala.concurrent.ExecutionContext.Implicits.global
import com.applaudostudios.store.domain._
import akka.actor.{ActorContext, ActorLogging, ActorRef, Props}
import akka.persistence.{PersistentActor, RecoveryCompleted, SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotOffer}
import com.applaudostudios.store.domain.categories.CategoryActor.LoadCategory
import com.applaudostudios.store.domain.data.Category
import com.applaudostudios.store.domain.items.ItemsManager.CategoryRemoved
import com.applaudostudios.store.domain.manager.EcommerceManager.{FinishBulkPersistence, InitBulkPersistence}

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Failure

object CategoriesManager{
  def props():Props = Props(CategoriesManager())
  //Command
  case object GetCategories
  case class GetCategory(id:BigInt)
  case class CreateCategory(category:Category)
  case class UpdateCategory(category: Category)
  case class RemoveCategory(id:BigInt, replyTo:ActorRef)
  case class VerifyCategory(catId:BigInt, ref:ActorRef)
  case class SetItemsManager(itemsManager:ActorRef)
  //Events
  case class CategoryCreated(category:Category)
  case class CategoryDisabled(id:BigInt)

  //Failures
  case class CategoryNotFoundException(msg:String) extends RuntimeException(msg)
  case class CategoryAlreadyRegisteredException(msg:String) extends RuntimeException(msg)
  case class CategoryAlreadyDeletedException(msg:String) extends RuntimeException(msg)
  case class CategoryAlreadyUpToDateException(msg:String) extends RuntimeException(msg)


  //State
  case class CategoriesManagerState(categories: mutable.Map[BigInt, ActorRef]= mutable.HashMap[BigInt,ActorRef](),
                                    disabledCategories:mutable.Set[BigInt] = mutable.HashSet[BigInt]()
                                   ){
    def turnIntoSnapshot: SnapshotContent = SnapshotContent(categories.keys.toList, disabledCategories )
  }
  case class SnapshotContent(categories: List[BigInt], disabledCategories:mutable.Set[BigInt]) {
    def turnIntoState(implicit context: ActorContext, store: ActorRef): CategoriesManagerState =
      CategoriesManagerState(
        categories.map(id => id -> context.actorOf(CategoryActor.props(id, store), s"CategoryActor-$id")).to(mutable.HashMap),
        disabledCategories
      )
  }

}

case class CategoriesManager() extends PersistentActor with ActorLogging{
  import CategoriesManager._
  override def persistenceId: String = "Categories-Manager-Actor"
  var state:CategoriesManagerState = CategoriesManagerState()

  override def receiveCommand: Receive = {
    case VerifyCategory(catId, ref)  =>
      sender() ! (state.categories.contains(catId) && !state.disabledCategories.contains(catId), ref)
    case InitBulkPersistence =>
      context.become(loadBulkData)
    case GetCategories =>
      sender() ! Future.sequence(state.categories.values.map(_ ? RetrieveInfo))
    case CreateCategory(cat) if !state.categories.contains(cat.id) =>
      persist(CategoryCreated(cat)) { _ =>
        val catActor = context.actorOf(CategoryActor.props(cat.id, self), s"CategoryActor-${cat.id}")
        catActor forward LoadCategory(cat)
        state.categories.addOne(cat.id -> catActor)
        isSnapshotTime()
      }
    case UpdateCategory(cat) if state.categories.contains(cat.id) && !state.disabledCategories.contains(cat.id)  =>
      state.categories(cat.id) forward UpdateCategory(cat)
    case GetCategory(id) if state.categories.contains(id) && !state.disabledCategories.contains(id)=>
      state.categories(id) forward RetrieveInfo

    case RemoveCategory(id, _) if state.disabledCategories.contains(id) =>
      sender() ! Failure(CategoryAlreadyDeletedException("Category already deleted"))
    case RemoveCategory(id, itemsManager) if state.categories.contains(id) =>
      persist(CategoryDisabled(id)) { _ =>
        state.disabledCategories.addOne(id)
        isSnapshotTime()
        itemsManager ! CategoryRemoved(id)
        state.categories(id) forward RetrieveInfo
      }
    case GetCategory(_) | UpdateCategory(_) | RemoveCategory(_,_) =>
        sender() ! Failure(CategoryNotFoundException("Category not found"))
    case CreateCategory(_) =>
      sender() ! Failure(CategoryAlreadyRegisteredException("Category already registered"))

    case SaveSnapshotSuccess(metadata) =>
      log.info(s"Saving snapshot succeeded: ${metadata.persistenceId} - ${metadata.timestamp}")
    case SaveSnapshotFailure(metadata, reason) =>
      log.warning(s"Saving snapshot failed: ${metadata.persistenceId} - ${metadata.timestamp} because of $reason.")
  }

  override def receiveRecover: Receive = {
    case CategoryCreated(cat)  =>
      val catActor = context.actorOf(CategoryActor.props(cat.id,self), s"CategoryActor-${cat.id}")
      state.categories.addOne(cat.id -> catActor)
    case CategoryDisabled(id) =>
      state.disabledCategories.addOne(id)
    case SnapshotOffer(metadata, contents:SnapshotContent) =>
      log.info(s"Recovered Snapshot for Actor: ${metadata.persistenceId} - ${metadata.timestamp}")
      state = contents.turnIntoState
    case RecoveryCompleted =>
      log.info("Recovery completed")
  }

  def loadBulkData: Receive = {
    case CreateCategory(cat) if !state.categories.contains(cat.id) =>
      persist(CategoryCreated(cat)) { _ =>
        val catActor = context.actorOf(CategoryActor.props(cat.id, self), s"CategoryActor-${cat.id}")
        log.debug(s"Loading the info: $cat")
        catActor ! LoadCategory(cat)
        state.categories.addOne(cat.id -> catActor)
        isSnapshotTime(SNAPSHOT_CATEGORIES_MANAGER_BULK_SIZE);
      }
    case CreateCategory(cat) =>
      state.categories(cat.id) ! UpdateCategory(cat)
    case FinishBulkPersistence =>
      context.become(receiveCommand)
    case SaveSnapshotSuccess(metadata) =>
      log.debug(s"Saving snapshot succeeded: ${metadata.persistenceId} - ${metadata.timestamp}")
    case SaveSnapshotFailure(metadata, reason) =>
      log.warning(s"Saving snapshot failed: ${metadata.persistenceId} - ${metadata.timestamp} because of $reason.")
    case x => log.debug(s"Unhandled $x")
  }

  def isSnapshotTime(size:Int = SNAPSHOT_CATEGORIES_MANAGER_SIZE): Unit = {
    if (lastSequenceNr % size == 0 && lastSequenceNr != 0) {
      saveSnapshot(state.turnIntoSnapshot)
    }
  }

}
