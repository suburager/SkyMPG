package org.mpgram.droid;

import java.util.ArrayList;

/**
 * Пул потоков с очередью "последним пришёл, первым обработан": при быстрой прокрутке сначала
 * грузится то, что только что появилось на экране, а не то, что уже давно прокручено.
 */
public class LifoExecutor {

    private final ArrayList<Runnable> stack = new ArrayList<Runnable>();
    private boolean stopped;

    public LifoExecutor(int threads) {
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    loop();
                }
            });
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.start();
        }
    }

    public void execute(Runnable r) {
        synchronized (stack) {
            if (stopped) {
                return;
            }
            stack.add(r);
            stack.notify();
        }
    }

    /** Выбрасывает ещё не начатые задачи (экран ушёл на задний план), потоки остаются. */
    public void clear() {
        synchronized (stack) {
            stack.clear();
        }
    }

    public void shutdown() {
        synchronized (stack) {
            stopped = true;
            stack.clear();
            stack.notifyAll();
        }
    }

    private void loop() {
        while (true) {
            Runnable r;
            synchronized (stack) {
                while (stack.isEmpty() && !stopped) {
                    try {
                        stack.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (stopped) {
                    return;
                }
                r = stack.remove(stack.size() - 1);
            }
            try {
                r.run();
            } catch (Throwable t) {
                // Рабочий поток не должен умирать из-за одной неудачной задачи
            }
        }
    }
}
