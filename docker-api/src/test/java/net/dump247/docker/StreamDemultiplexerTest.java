package net.dump247.docker;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.testng.Assert.assertEquals;

public class StreamDemultiplexerTest {
    private ExecutorService _executorService;

    @BeforeMethod
    public void setup() {
        _executorService = Executors.newSingleThreadExecutor();
    }

    @Test
    public void test() throws Exception {
        ByteArrayInputStream data = new ByteArrayInputStream(new byte[]{
                1, 0, 0, 0, // stream id
                0, 0, 0, 1, // length
                4,
                2, 0, 0, 0,
                0, 0, 0, 4,
                11, 12, 13, 14
        });

        StreamDemultiplexer demultiplexer = new StreamDemultiplexer(data);
        InputStream stderr = demultiplexer.getStderr();
        InputStream stdout = demultiplexer.getStdout();

        assertEquals(stdout.read(), 4);

        byte[] buf = new byte[10];
        assertEquals(stderr.read(buf), 4);
        assertEquals(buf, new byte[]{11, 12, 13, 14, 0, 0, 0, 0, 0, 0});
    }

    @Test(timeOut = 1000)
    public void threadedTest() throws Exception {
        ByteArrayInputStream data = new ByteArrayInputStream(new byte[]{
                1, 0, 0, 0, // stream id
                0, 0, 0, 1, // length
                4,
                2, 0, 0, 0,
                0, 0, 0, 4,
                11, 12, 13, 14
        });


        StreamDemultiplexer demultiplexer = new StreamDemultiplexer(data);
        final InputStream stderr = demultiplexer.getStderr();
        final InputStream stdout = demultiplexer.getStdout();

        Future<Integer> result = _executorService.submit(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                return stdout.read();
            }
        });

        byte[] buf = new byte[10];
        assertEquals(stderr.read(buf), 4);
        assertEquals(buf, new byte[]{11, 12, 13, 14, 0, 0, 0, 0, 0, 0});

        assertEquals((int) result.get(), 4);
    }
}
