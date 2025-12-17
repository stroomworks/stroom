package stroom.dashboard.impl.db;

import stroom.dashboard.impl.visualisation.VisualisationAssetDao;
import stroom.visualisation.shared.VisualisationAsset;
import stroom.visualisation.shared.VisualisationAssets;

import com.google.inject.Guice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

/**
 * Tests the Visualisation DAO.
 */
@ExtendWith(MockitoExtension.class)
public class TestVisualisationDao {

    @SuppressWarnings("unused")
    @Inject
    private VisualisationAssetDao assetDao;

    @BeforeEach
    void setup() {
        Guice.createInjector(new TestModule()).injectMembers(this);
    }

    @Test
    public void testDao() throws IOException {
        final String ownerUuid = "123456";

        final VisualisationAssets assets = assetDao.fetchAssets(ownerUuid);
        assertThat(assets.getAssets().size()).isEqualTo(0);

        final VisualisationAsset assetOne =
                new VisualisationAsset("assetOne", "/assetOne/one.html", false);
        assets.addAsset(assetOne);
        assetDao.storeAssets(ownerUuid, assets);

        final VisualisationAssets updatedAssets = assetDao.fetchAssets(ownerUuid);
        assertThat(updatedAssets.getAssets().size()).isEqualTo(1);
        assertThat(updatedAssets.getAssets().getFirst().getPath()).isEqualTo("/assetOne/one.html");
    }
}
