package com.mariadbbukkit.domain.model.provision;

import com.mariadbbukkit.domain.model.database.DatabaseConfig;
import java.io.IOException;
import java.nio.file.Path;

public interface Provisioner {
    Path ensureBinaries(DatabaseConfig config) throws IOException;
    void ensureDataDir(DatabaseConfig config) throws IOException;
}
