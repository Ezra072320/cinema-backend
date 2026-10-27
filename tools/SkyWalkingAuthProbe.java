/** Keeps an agent alive long enough to verify rejection of an invalid OAP token.
 * No business application, HTTP listener, database or credentials are used.
 */
public final class SkyWalkingAuthProbe {
    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(25_000);
    }
}
