package edu.czu.cinema.domain.movie.sync;

/** 外部 ID 始终与来源一起传递，不能把 TMDB ID 当成猫眼 ID。 */
public record MovieSyncCandidate(String source, int id, String title, String releaseDate) {
    public static MovieSyncCandidate maoyan(MaoyanClient.Candidate candidate) {
        return new MovieSyncCandidate("MAOYAN",candidate.id(),candidate.title(),candidate.releaseDate());
    }
    public MaoyanClient.Candidate maoyan() { return new MaoyanClient.Candidate(id,title,releaseDate); }
}
