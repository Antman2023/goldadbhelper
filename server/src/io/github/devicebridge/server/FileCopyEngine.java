package io.github.devicebridge.server;

import java.io.*;
import java.util.*;

/** Bounded, no-overwrite tree copy. Destination entries are never removed on failure.
 * Planning is read-only. A directory is successful only if every planned descendant is
 * acknowledged and its source membership is unchanged at the end. This is not a filesystem
 * snapshot: same-UID/root concurrent mutations and directory mkdir-to-open remain boundaries.
 */
final class FileCopyEngine {
    interface Directory extends Closeable {
        Info stat(String name) throws IOException;
        Directory open(String name, String identity) throws IOException;
        Directory create(String name) throws IOException;
        String[] names() throws IOException;
        String child(String name) throws IOException;
    }
    static final class Info {
        final String token, identity;
        final boolean regular, directory;
        Info(String token, String identity, boolean regular, boolean directory) {
            this.token=token; this.identity=identity; this.regular=regular; this.directory=directory;
        }
    }
    interface Observer {
        void event(String event, Node node, String status, String message, FileCommitServer.Receipt receipt) throws IOException;
        void check() throws IOException;
    }
    static final class Node {
        final String name, source, destination;
        final List<Node> children=new ArrayList<Node>();
        Info info; String[] names; String error; String status="PLANNED";
        Node(String name,String source,String destination){this.name=name;this.source=source;this.destination=destination;}
    }
    final List<Node> nodes=new ArrayList<Node>();
    private final FileCommitServer.Fs fs;
    private final Observer observer;
    private final int limit, maxDepth;
    FileCopyEngine(FileCommitServer.Fs fs, Observer observer) { this(fs,observer,10000,64); }
    FileCopyEngine(FileCommitServer.Fs fs, Observer observer, int limit, int maxDepth) {
        this.fs=fs;this.observer=observer;this.limit=limit;this.maxDepth=maxDepth;
    }
    Node plan(Directory source,String name,String displaySource,String displayDestination,int depth)throws IOException {
        observer.check();
        if(nodes.size()>=limit||depth>maxDepth)throw new PlanLimit(new IOException("Copy plan exceeds entry/depth limit; no copy was started"));
        Node n=new Node(name,displaySource,displayDestination);nodes.add(n);
        observer.event("plan",n,"PLANNED","",null);
        try {
            n.info=source.stat(name);
            if(n.info.directory){
                try(Directory directory=source.open(name,n.info.identity)){
                    n.names=names(directory);
                    for(String child:n.names)n.children.add(plan(directory,child,join(displaySource,child),join(displayDestination,child),depth+1));
                }
            } else if(!n.info.regular)n.error="Unsupported type; only ordinary files and directories are copied";
        } catch(PlanLimit failure){throw failure;}
          catch(IOException failure){
            n.error=failure.getMessage();
        }
        return n;
    }
    boolean copy(Node node,Directory source,Directory destination,String destinationName)throws IOException {
        observer.check();
        if(node.error!=null){finish(node,"FAILED",node.error,null);skipChildren(node,"Source could not be planned");return false;}
        observer.event("start",node,"STARTED","",null);node.status="STARTED";
        boolean created=false;
        try {
            Info present=source.stat(node.name);
            if(!node.info.token.equals(present.token))throw new IOException("Planned source changed before copying");
            if(node.info.regular){
                String input=source.child(node.name),output=destination.child(destinationName);
                FileCommitServer.Receipt before=FileCommitServer.hash(fs,input);
                if(!node.info.token.equals(before.snapshot))throw new IOException("Planned source changed before hashing");
                FileCommitServer.Receipt receipt=FileCommitServer.commit(fs,input,output,before.sha256,before.bytes,node.info.token);
                finish(node,"COPIED","Verified SHA-256 and length",receipt);return true;
            }
            boolean complete=true;
            try(Directory input=source.open(node.name,node.info.identity)){
                if(!Arrays.equals(node.names,names(input)))throw new IOException("Source directory membership changed before copying");
                // mkdir is exclusive; create() must never open an existing destination on EEXIST.
                Directory opened=destination.create(destinationName);created=true;
                try(Directory output=opened){
                    for(Node child:node.children){observer.check();if(!copy(child,input,output,child.name))complete=false;}
                    if(!Arrays.equals(node.names,names(input)))complete=false;
                    if(!node.info.identity.equals(source.stat(node.name).identity))complete=false;
                }
            }
            finish(node,complete?"DIRECTORY_COPIED":"PARTIAL",complete?"Every planned child copied":"Directory is partial or source membership changed; completed targets retained",null);
            return complete;
        } catch(FileCommitServer.CommitFailure failure){
            finish(node,"UNKNOWN".equals(failure.outcome)?"UNKNOWN":"FAILED",failure.getMessage(),null);
        } catch(CreatedDirectoryFailure failure){
            finish(node,"UNKNOWN",failure.getMessage(),null);
        } catch(IOException failure){
            finish(node,created?"PARTIAL":"FAILED",failure.getMessage(),null);
        }
        skipChildren(node,"Parent did not complete; already completed children are retained");
        return false;
    }
    void abortPlanning(String message)throws IOException {
        for(Node node:nodes)if("PLANNED".equals(node.status))finish(node,"NOT_EXECUTED",message,null);
    }
    private void skipChildren(Node n,String why)throws IOException {
        for(Node child:n.children){if("PLANNED".equals(child.status))finish(child,"NOT_EXECUTED",why,null);skipChildren(child,why);}
    }
    private void finish(Node node,String status,String message,FileCommitServer.Receipt receipt)throws IOException {
        node.status=status;observer.event("result",node,status,message==null?"Unspecified filesystem failure":message,receipt);
    }
    private static String[] names(Directory directory)throws IOException {
        String[] names=directory.names();if(names==null)throw new IOException("Source directory cannot be listed");
        Set<String> unique=new HashSet<String>();
        for(String name:names){component(name);if(!unique.add(name))throw new IOException("Duplicate or undecodable directory names");}
        Arrays.sort(names);return names;
    }
    static String component(String name)throws IOException {
        if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\0')>=0)throw new IOException("Invalid path component");
        return name;
    }
    static String join(String parent,String child)throws IOException{return (parent.endsWith("/")?parent:parent+"/")+component(child);}
    static final class CreatedDirectoryFailure extends IOException { CreatedDirectoryFailure(IOException cause){super("New directory may exist; retained for inspection: "+cause.getMessage(),cause);} }
    private static final class PlanLimit extends IOException { PlanLimit(IOException cause){super(cause.getMessage(),cause);} }
}
