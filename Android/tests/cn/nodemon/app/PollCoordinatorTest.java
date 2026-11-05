package cn.nodemon.app;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class PollCoordinatorTest {
    public static void main(String[] args)throws Exception{
        PollCoordinator.Lease ui=PollCoordinator.acquire("host");if(ui==null||PollCoordinator.acquire("host")!=null)throw new AssertionError("overlapping host request");
        PollCoordinator.Lease other=PollCoordinator.acquire("other");if(other==null)throw new AssertionError("different host blocked");other.close();ui.close();
        PollCoordinator.Lease newer=PollCoordinator.acquire("host");ui.close();if(PollCoordinator.acquire("host")!=null)throw new AssertionError("old completion released newer owner");newer.close();
        AtomicInteger acquired=new AtomicInteger();CountDownLatch start=new CountDownLatch(1),held=new CountDownLatch(1);Thread[] workers=new Thread[12];
        for(int i=0;i<workers.length;i++){workers[i]=new Thread(()->{try{start.await();PollCoordinator.Lease lease=PollCoordinator.acquire("shared");if(lease!=null){acquired.incrementAndGet();held.await();lease.close();}}catch(InterruptedException e){throw new RuntimeException(e);}});workers[i].start();}
        start.countDown();while(acquired.get()==0)Thread.yield();Thread.sleep(80);if(acquired.get()!=1)throw new AssertionError("concurrent owners");held.countDown();for(Thread thread:workers)thread.join();
        System.out.println("Shared UI/background/widget poll ownership passed.");
    }
}
