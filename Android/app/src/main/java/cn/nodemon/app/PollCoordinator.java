package cn.nodemon.app;

import java.util.concurrent.ConcurrentHashMap;

/** One in-process request and database write per host across UI, jobs and widgets. */
final class PollCoordinator {
    private static final ConcurrentHashMap<String,Lease> active=new ConcurrentHashMap<>();
    static Lease acquire(String host){Lease lease=new Lease(host);return active.putIfAbsent(host,lease)==null?lease:null;}
    static final class Lease implements AutoCloseable {
        private final String host;
        private Lease(String host){this.host=host;}
        @Override public void close(){active.remove(host,this);}
    }
}
