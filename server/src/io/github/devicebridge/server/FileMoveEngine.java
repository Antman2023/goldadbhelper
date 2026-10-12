package io.github.devicebridge.server;

import java.io.*;

/** Verification wraps the existing modern no-clobber command. A successful process exit alone
 * is never success. Cross-filesystem moves are deliberately UNKNOWN, even if content matches. */
final class FileMoveEngine {
    interface Backend {
        FileCopyEngine.Info stat(boolean destination)throws IOException;
        FileCommitServer.Receipt hash(boolean destination)throws IOException;
        String move()throws IOException;
    }
    static final class Result {
        final String status,message;final FileCommitServer.Receipt receipt;
        Result(String status,String message,FileCommitServer.Receipt receipt){this.status=status;this.message=message;this.receipt=receipt;}
    }
    static Result run(Backend backend){
        boolean issued=false;
        try {
            FileCopyEngine.Info before=backend.stat(false);
            if(!before.regular&&!before.directory)throw new IOException("Only ordinary files and directories are supported for verified move");
            try{backend.stat(true);return new Result("NOT_EXECUTED","Exact destination already exists; move was not issued",null);}
            catch(FileNotFoundException absent){}
            FileCommitServer.Receipt content=before.regular?backend.hash(false):null;
            if(content!=null&&!content.snapshot.equals(before.token))throw new IOException("Source changed before move");
            // Re-read source metadata immediately before command dispatch. This is still a path
            // operation, not an atomic compare-and-rename primitive.
            if(!before.token.equals(backend.stat(false).token))throw new IOException("Source changed before move");
            issued=true;String commandResult=backend.move();
            try{backend.stat(false);return new Result("UNKNOWN","Source path still exists; move is not verified. "+commandResult,null);}
            catch(FileNotFoundException absent){}
            FileCopyEngine.Info after=backend.stat(true);
            if(!before.identity.equals(after.identity)||before.regular!=after.regular||before.directory!=after.directory)
                return new Result("UNKNOWN","Exact destination identity differs. Cross-filesystem or redirected outcome requires inspection. "+commandResult,null);
            FileCommitServer.Receipt persisted=before.regular?backend.hash(true):null;
            if(content!=null&&(content.bytes!=persisted.bytes||!content.sha256.equals(persisted.sha256)))
                return new Result("UNKNOWN","Exact destination content differs; inspect both paths",null);
            if(!after.token.equals(backend.stat(true).token))return new Result("UNKNOWN","Exact destination changed during verification",null);
            try{backend.stat(false);return new Result("UNKNOWN","Source path exists again after verification",null);}
            catch(FileNotFoundException absent){}
            return new Result("VERIFIED","Source absent and exact destination identity"+(before.regular?", SHA-256 and length":"")+" verified",persisted);
        }catch(IOException|RuntimeException failure){return new Result(issued?"UNKNOWN":"NOT_EXECUTED",String.valueOf(failure.getMessage()),null);}
    }
}
