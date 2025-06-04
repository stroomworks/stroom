package stroom.util.io;


import stroom.util.shared.NullSafe;

import java.util.Objects;

public class FileName {

    private final String fullName;
    private final String baseName;
    private final String extension;

    private FileName(final String fullName, final String baseName, final String extension) {
        this.fullName = fullName;
        this.baseName = baseName;
        this.extension = extension;
    }

    public static FileName fromParts(final String baseName, final String extension) {
        if (baseName == null) {
            if (NullSafe.isEmptyString(extension)) {
                return new FileName("", "", "");
            } else {
                return new FileName("." + extension, "", extension);
            }
        } else {
            if (NullSafe.isEmptyString(extension)) {
                return new FileName(baseName, baseName, "");
            } else {
                return new FileName(baseName + "." + extension, baseName, extension);
            }
        }
    }

    public static FileName parse(final String fileName) {
        Objects.requireNonNull(fileName, "fileName is null");
        final int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex != -1) {
            final String baseName = fileName.substring(0, dotIndex);
            final String extension = fileName.substring(dotIndex + 1);
            return new FileName(fileName, baseName, extension);
        } else {
            return new FileName(fileName, fileName, "");
        }
    }

    public String getFullName() {
        return fullName;
    }

    public String getBaseName() {
        return baseName;
    }

    public String getExtension() {
        return extension;
    }

    public boolean hasExtension() {
        return extension != null && !extension.isEmpty();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FileName fileName = (FileName) o;
        return Objects.equals(fullName, fileName.fullName) &&
               Objects.equals(baseName, fileName.baseName) &&
               Objects.equals(extension, fileName.extension);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fullName, baseName, extension);
    }

    @Override
    public String toString() {
        return "FileName{" +
               "fullName='" + fullName + '\'' +
               ", baseName='" + baseName + '\'' +
               ", extension='" + extension + '\'' +
               '}';
    }
}
