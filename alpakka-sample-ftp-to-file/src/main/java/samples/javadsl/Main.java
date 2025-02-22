package samples.javadsl;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.IOResult;
import akka.stream.Materializer;
// #imports
import akka.stream.alpakka.ftp.FtpSettings;
import akka.stream.alpakka.ftp.javadsl.Ftp;
import akka.stream.javadsl.FileIO;
import akka.stream.javadsl.Sink;
// #imports
import org.apache.ftpserver.FtpServer;
import org.apache.mina.util.AvailablePortFinder;
import playground.FtpServerEmbedded;
import playground.filesystem.FileSystemMock;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;


public class Main {

    final ActorSystem actorSystem = ActorSystem.create();
    final Materializer materializer = ActorMaterializer.create(actorSystem);

    private void run() throws UnknownHostException {
        final FileSystemMock ftpFileSystem = new FileSystemMock();

        final int port = AvailablePortFinder.getNextAvailable(21000);
        final FtpServer ftpServer = FtpServerEmbedded.start(ftpFileSystem.fileSystem, port);

        ftpFileSystem.generateFiles(30, 10, "/home/anonymous");
        ftpFileSystem.putFileOnFtp("/home/anonymous", "hello.txt");
        ftpFileSystem.putFileOnFtp("/home/anonymous", "hello2.txt");

        // #sample
        final FtpSettings ftpSettings =
                FtpSettings.create(InetAddress.getByName("localhost")).withPort(port);
        final int parallelism = 5;

        // #sample

        final Path targetDir = Paths.get("target/");
        // #sample
        final CompletionStage<List<Pair<String, IOResult>>> fetchedFiles =
                Ftp.ls("/", ftpSettings) // : FtpFile (1)
                        .filter(ftpFile -> ftpFile.isFile()) // : FtpFile (2)
                        .mapAsyncUnordered(
                                parallelism,
                                ftpFile -> { // (3)
                                    final Path localPath = targetDir.resolve("." + ftpFile.path());
                                    final CompletionStage<IOResult> fetchFile =
                                            Ftp.fromPath(ftpFile.path(), ftpSettings)
                                                    .runWith(FileIO.toPath(localPath), materializer); // (4)
                                    return fetchFile.thenApply(
                                            ioResult -> // (5)
                                                    Pair.create(ftpFile.path(), ioResult));
                                }) // : (String, IOResult)
                        .runWith(Sink.seq(), materializer); // (6)
        // #sample

        fetchedFiles
                .thenApply(
                        files ->
                                files.stream()
                                        .filter(pathResult -> !pathResult.second().wasSuccessful())
                                        .collect(Collectors.toList()))
                .whenComplete(
                        (res, ex) -> {
                            if (res != null) {
                                if (res.isEmpty()) {
                                    System.out.println("all files fetched");
                                } else {
                                    System.out.println("errors occured: " + res.toString());
                                }
                            } else {
                                System.out.println("the stream failed");
                            }

                            actorSystem.terminate();
                            actorSystem.getWhenTerminated().thenAccept(t -> ftpServer.stop());
                        });
    }

    public static void main(String[] args) throws UnknownHostException {
        new Main().run();
    }
}
