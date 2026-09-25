package com.mars.harness.kernel.ports.identity;

import com.mars.harness.kernel.core.identity.IdentityRegistry;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the unified identity plane: Bootshift's FILE_ID registry plus the kernel's
 * sub-file registry. {@link #registry()} returns a <em>copy</em>, so a capability cannot mutate
 * identity. Only the kernel reattaches.
 */
public interface IdentityView {

    record FileInfo(String fileId, String path, String module, String role, String sha256, String status,
                    String language) {
    }

    Optional<FileInfo> fileByPath(String path);

    Optional<FileInfo> fileById(String fileId);

    List<FileInfo> activeFiles();

    IdentityRegistry registry();
}
