package app.quorum.auth.repository;

import app.quorum.auth.domain.RefreshToken;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {}
