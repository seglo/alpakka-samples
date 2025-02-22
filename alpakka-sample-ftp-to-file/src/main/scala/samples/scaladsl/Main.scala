package samples.scaladsl

import java.net.InetAddress
import java.nio.file.Paths

import akka.actor.ActorSystem
// #imports
import akka.stream.alpakka.ftp.FtpSettings
import akka.stream.alpakka.ftp.scaladsl.Ftp
import akka.stream.scaladsl.{FileIO, Sink}
import akka.stream.{ActorMaterializer, IOResult, Materializer}
// #imports
import org.apache.mina.util.AvailablePortFinder
import playground.FtpServerEmbedded
import playground.filesystem.FileSystemMock

import scala.collection.immutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

object Main extends App {
  implicit val actorSystem: ActorSystem = ActorSystem()
  implicit val actorMaterializer: Materializer = ActorMaterializer()
  implicit val executionContext: ExecutionContext = actorSystem.dispatcher

  def wait(duration: FiniteDuration): Unit = Thread.sleep(duration.toMillis)

  def terminateActorSystem(): Unit =
    Await.result(actorSystem.terminate(), 1.seconds)

  val ftpFileSystem = new FileSystemMock()

  val port = AvailablePortFinder.getNextAvailable(21000)
  val ftpServer = FtpServerEmbedded.start(ftpFileSystem.fileSystem, port)

  ftpFileSystem.generateFiles(30, 10, "/home/anonymous")
  ftpFileSystem.putFileOnFtp("/home/anonymous", "hello.txt")
  ftpFileSystem.putFileOnFtp("/home/anonymous", "hello2.txt")

  // #sample
  val ftpSettings = FtpSettings(InetAddress.getByName("localhost")).withPort(port)

  // #sample

  val targetDir = Paths.get("target/")
  // format: off
  // #sample
  val fetchedFiles: Future[immutable.Seq[(String, IOResult)]] =
    Ftp
      .ls("/", ftpSettings)                                    //: FtpFile (1)
      .filter(ftpFile => ftpFile.isFile)                       //: FtpFile (2)
      .mapAsyncUnordered(parallelism = 5) { ftpFile =>         // (3)
        val localPath = targetDir.resolve("." + ftpFile.path)
        val fetchFile: Future[IOResult] = Ftp
          .fromPath(ftpFile.path, ftpSettings)
          .runWith(FileIO.toPath(localPath))                   // (4)
        fetchFile.map { ioResult =>                            // (5)
          (ftpFile.path, ioResult)
        }
      }                                                        //: (String, IOResult)
      .runWith(Sink.seq)                                       // (6)
  // #sample
  // format: on
  fetchedFiles
    .map { files =>
      files.filter { case (_, r) => !r.wasSuccessful }
    }
    .onComplete { res =>
      res match {
        case Success(errors) if errors.isEmpty =>
          println("all files fetched.")
        case Success(errors) =>
          println(s"errors occured: ${errors.mkString("\n")}")
        case Failure(exception) =>
          println("the stream failed")
      }
      actorSystem.terminate().onComplete { _ =>
        ftpServer.stop()
      }
    }
}
