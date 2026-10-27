import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSSClientBuilder;

/** Only deletes the random image created by file_local_acceptance.py, using the existing OSS SDK. */
class OssProbeCleanup {
    public static void main(String[] args) {
        String key = System.getenv("CINEMA_OSS_PROBE_KEY");
        if (key == null || !key.matches("cinema/\\d{4}/\\d{2}/\\d{2}/[a-f0-9]{32}\\.png"))
            throw new IllegalArgumentException("Not an acceptance probe key");
        var config = new ClientBuilderConfiguration();
        config.setConnectionTimeout(3000);
        config.setSocketTimeout(15000);
        config.setMaxErrorRetry(0);
        String endpoint = System.getenv("ALIYUN_OSS_ENDPOINT");
        if (!endpoint.contains("://")) endpoint = "https://" + endpoint;
        var oss = new OSSClientBuilder().build(endpoint, System.getenv("ALIYUN_OSS_AK"),
                System.getenv("ALIYUN_OSS_SK"), config);
        try {
            String bucket = System.getenv("ALIYUN_OSS_BUCKET");
            oss.deleteObject(bucket, key);
            if (oss.doesObjectExist(bucket, key)) throw new IllegalStateException("Probe was not removed");
        } finally { oss.shutdown(); }
    }
}
