
package org.CapitalClient.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.CapitalClient.model.ProfileSelection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class ProfileService {

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private final Path profileFile = Path.of(
            System.getProperty("user.home"),
            ".capitalclient",
            "selected-profile.json"
    );

    public void save(ProfileSelection profile) throws IOException {

        Files.createDirectories(profileFile.getParent());

        Files.writeString(
                profileFile,
                gson.toJson(profile)
        );
    }

    public Optional<ProfileSelection> load() throws IOException {

        if (!Files.exists(profileFile)) {
            return Optional.empty();
        }

        String json = Files.readString(profileFile);

        ProfileSelection profile = gson.fromJson(
                json,
                ProfileSelection.class
        );

        return Optional.ofNullable(profile);
    }
}
