package io.github.devicebridge.android;

/** Actual production journal report mapping; pre-populated receipts, no Android JSON/runtime I/O. */
public final class FileCopyCompletionTests {
    private static int assertions;
    private static final String SHA="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    public static void main(String[] args) throws Exception {
        completeResults(); failedExit(); partialResults(); missingReceipt();
        System.out.println("FileCopyCompletionTests: " + assertions + " assertions passed (production report mapping only; no JSON/device verification)");
    }

    private static void completeResults() {
        for(int exit:new int[]{0,-1}) {
            FileCopyManager.Report report=complete().report("",exit);
            check(report.complete,"verified receipt with successful or legacy-unknown exit completes copy");
            check(report.warning.isEmpty()&&report.items.get(0).sha256.equals(SHA),"verified digest and clean result preserved");
        }
        check(complete().report("").complete,"legacy report overload retains verified receipt semantics");
    }

    private static void failedExit() {
        for(int exit:new int[]{1,17,255,-2}) {
            FileCopyManager.Report report=complete().report("",exit);
            check(!report.complete,"known nonzero exit cannot count as overall success");
            check(report.warning.contains(String.valueOf(exit)),"process failure reports exact exit status");
            check(report.items.get(0).status.equals("COPIED")&&report.items.get(0).sha256.equals(SHA),"process failure does not revoke verified per-file receipt");
        }
        FileCopyManager.Report cancelled=complete().report("synthetic cancellation",7);
        check(!cancelled.complete&&cancelled.warning.contains("synthetic cancellation")&&cancelled.warning.contains("7"),"transport failure and process status both retained");
    }

    private static void partialResults() {
        FileCopyManager.Journal journal=complete();journal.complete=false;
        FileCopyManager.Item item=new FileCopyManager.Item("/source/other","/target/other");item.status="NOT_EXECUTED";journal.items.put(item.source,item);
        FileCopyManager.Report report=journal.report("",0);
        check(!report.complete,"successful process exit does not turn partial receipts into success");
        check(report.items.get(0).status.equals("COPIED")&&report.items.get(1).status.equals("NOT_EXECUTED"),"partial results preserve completed and unexecuted entries");
    }

    private static void missingReceipt() {
        FileCopyManager.Journal journal=complete();journal.done=false;
        FileCopyManager.Item item=new FileCopyManager.Item("/source/pending","/target/pending");item.status="STARTED";journal.items.put(item.source,item);
        FileCopyManager.Report report=journal.report("",0);
        check(!report.complete,"successful process exit cannot replace final acknowledgement");
        check(report.items.get(0).status.equals("COPIED")&&report.items.get(1).status.equals("UNKNOWN"),"missing completion preserves known file and marks in-flight result unknown");
        FileCopyManager.Journal empty=new FileCopyManager.Journal("fixture","/source","/target");
        FileCopyManager.Report noReceipt=empty.report("",17);
        check(!noReceipt.complete&&noReceipt.items.get(0).status.equals("UNKNOWN"),"process failure without receipt cannot imply no mutation");
    }

    private static FileCopyManager.Journal complete() {
        FileCopyManager.Journal journal=new FileCopyManager.Journal("fixture","/source","/target");
        FileCopyManager.Item file=new FileCopyManager.Item("/source/file","/target/file");file.status="COPIED";file.sha256=SHA;file.bytes=0;
        journal.items.put(file.source,file);journal.done=true;journal.complete=true;return journal;
    }
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
}
