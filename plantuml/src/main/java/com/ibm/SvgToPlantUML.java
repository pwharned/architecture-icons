package com.ibm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
public class SvgToPlantUML {
    public static void main(String[] args) {
        // Force headless mode
        System.setProperty("java.awt.headless", "true");
        if (args.length < 2) {
            System.out.println("Usage: java -jar svg-to-plantuml.jar <svg-directory> <output-directory>");
            return;
        }
        String svgDir = args[0];
        String outputDir = args[1];

        // Create output directory
        new File(outputDir).mkdirs();

        try {
            // Get the absolute paths for cleaner relative path calculation
            Path svgDirPath = Paths.get(svgDir).toAbsolutePath().normalize();
            Path outputDirPath = Paths.get(outputDir).toAbsolutePath().normalize();
            System.out.println("Scanning directory: " + svgDirPath);
            // Use try-with-resources to ensure the stream is closed
            try (Stream<Path> paths = Files.walk(svgDirPath)) {
                List<Path> svgFiles = paths
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".svg"))
                        .collect(Collectors.toList());
                System.out.println("Found " + svgFiles.size() + " SVG files to process");
                // Check for available conversion tools
                String conversionTool = findConversionTool();
                if (conversionTool == null) {
                    System.err.println("ERROR: No suitable SVG to PNG conversion tool found.");
                    System.err.println("Please install Inkscape, ImageMagick, or rsvg-convert.");
                    return;
                }
                System.out.println("Using conversion tool: " + conversionTool);
                int success = 0;
                int failed = 0;
                for (Path svgPath : svgFiles) {
                    // Calculate relative path from the source directory
                    Path relativePath = svgDirPath.relativize(svgPath);
                    // Create the destination paths with same directory structure
                    String outputFilename = svgPath.getFileName().toString().replaceAll("\\.svg$", ".puml");
                    String pngFilename = svgPath.getFileName().toString().replaceAll("\\.svg$", ".png");
                    Path targetDir = outputDirPath.resolve(relativePath).getParent();
                    Path outputPath = targetDir.resolve(outputFilename);
                    Path pngPath = targetDir.resolve(pngFilename);
                    // Generate sprite name - use relative path for uniqueness
                    String spriteName = relativePath.toString()
                            .replaceAll("\\.svg$", "")
                            .replaceAll("[^a-zA-Z0-9_]", "_");
                    // Ensure the target directory exists
                    Files.createDirectories(targetDir);
                    try {
                        // Step 1: Convert SVG to PNG using external tool
                        boolean conversionSuccess = convertSvgToPng(conversionTool, svgPath, pngPath);
                        if (!conversionSuccess) {
                            throw new Exception("SVG to PNG conversion failed");
                        }

                        // Step 2: Read the PNG file
                        BufferedImage image = ImageIO.read(pngPath.toFile());
                        if (image == null) {
                            throw new Exception("Failed to read PNG image");
                        }

                        // Step 3: Create PlantUML sprite from the PNG
                        try (FileWriter writer = new FileWriter(outputPath.toFile())) {
                            writer.write("@startuml\n");

                            // Write sprite definition
                            writer.write("sprite $" + spriteName + " [\n");
                            int width = image.getWidth();
                            int height = image.getHeight();

                            // Process the image data into PlantUML sprite format
                            for (int y = 0; y < height; y++) {
                                StringBuilder line = new StringBuilder();
                                for (int x = 0; x < width; x++) {
                                    int rgb = image.getRGB(x, y);
                                    int alpha = (rgb >> 24) & 0xff;

                                    // Convert pixel to hexadecimal color or space for transparency
                                    if (alpha < 128) {
                                        line.append(" "); // Transparent
                                    } else {
                                        // Get color components
                                        int r = (rgb >> 16) & 0xff;
                                        int g = (rgb >> 8) & 0xff;
                                        int b = rgb & 0xff;

                                        // Simplified color mapping for PlantUML sprite
                                        if (r > 200 && g > 200 && b > 200) {
                                            line.append("0"); // White/light
                                        } else {
                                            line.append("F"); // Dark
                                        }
                                    }
                                }
                                writer.write(line.toString() + "\n");
                            }
                            writer.write("]\n\n");

                            writer.write("@enduml\n");
                        }
                        System.out.println("Converted: " + relativePath);
                        success++;

                    } catch (Exception e) {
                        System.err.println("Error processing " + relativePath + ": " + e.getMessage());
                        e.printStackTrace();
                        failed++;
                    }
                }
                System.out.println("\nConversion complete:");
                System.out.println("- Successfully processed: " + success + " files");
                System.out.println("- Failed: " + failed + " files");
                System.out.println("Output directory: " + outputDirPath);
                // Generate an index file if requested
                if (success > 0) {
                    generateIndexFile(outputDirPath);
                }
            }
        } catch (IOException e) {
            System.err.println("Error accessing directory: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Find an available SVG to PNG conversion tool
     */
    private static String findConversionTool() {
        // Check for common SVG to PNG conversion tools
        if (isCommandAvailable("inkscape")) {
            return "inkscape";
        }
        if (isCommandAvailable("convert")) {
            return "convert";
        }
        if (isCommandAvailable("rsvg-convert")) {
            return "rsvg-convert";
        }
        return null;
    }

    /**
     * Check if a command is available in the system
     */
    private static boolean isCommandAvailable(String cmd) {
        try {
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("where", cmd);
            } else {
                pb = new ProcessBuilder("which", cmd);
            }
            Process p = pb.start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Converts SVG to PNG using available external tool
     */
    private static boolean convertSvgToPng(String tool, Path svgPath, Path pngPath) throws Exception {
        ProcessBuilder pb = null;
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        switch (tool) {
            case "inkscape":
                // Use Inkscape (better SVG rendering)
                if (isWindows) {
                    pb = new ProcessBuilder(
                            "inkscape",
                            "--export-filename=" + pngPath.toString(),
                            "--export-width=64",
                            svgPath.toString()
                    );
                } else {
                    pb = new ProcessBuilder(
                            "inkscape",
                            "-o", pngPath.toString(),
                            "-w", "64",
                            svgPath.toString()
                    );
                }
                break;
            case "convert":
                // Use ImageMagick
                pb = new ProcessBuilder(
                        "convert",
                        "-background", "none",
                        "-density", "300",
                        "-resize", "64x64",
                        svgPath.toString(),
                        pngPath.toString()
                );
                break;
            case "rsvg-convert":
                // Use rsvg-convert
                pb = new ProcessBuilder(
                        "rsvg-convert",
                        "-w", "64",
                        "-h", "64",
                        "-o", pngPath.toString(),
                        svgPath.toString()
                );
                break;
        }

        if (pb == null) {
            return false;
        }

        // Redirect error stream to capture any issues
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // Log the output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(tool + ": " + line);
            }
        }

        int exitCode = p.waitFor();
        return exitCode == 0;
    }

    /**
     * Reads file content in a way compatible with both older and newer Java versions
     */
    private static String readFileContent(Path path) throws IOException {
        byte[] encoded = Files.readAllBytes(path);
        return new String(encoded, StandardCharsets.UTF_8);
    }
    /**
     * Generates an index file that includes all PUML files
     */
    private static void generateIndexFile(Path outputDir) {
        try {
            Path indexPath = outputDir.resolve("all_sprites.puml");
            try (Stream<Path> paths = Files.walk(outputDir);
                 FileWriter writer = new FileWriter(indexPath.toFile())) {
                // Start the file
                writer.write("@startuml\n");
                writer.write("' Index file for all generated sprites\n\n");
                // Include all puml files
                List<Path> pumlFiles = paths
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().toLowerCase().endsWith(".puml"))
                        .filter(p -> !p.equals(indexPath)) // Exclude the index file itself
                        .sorted()
                        .collect(Collectors.toList());
                for (Path pumlFile : pumlFiles) {
                    // Calculate relative path from output directory
                    Path relativePath = outputDir.relativize(pumlFile);
                    writer.write("!include " + relativePath.toString().replace('\\', '/') + "\n");
                }
                // End the file
                writer.write("\n@enduml\n");
                System.out.println("Generated index file with " + pumlFiles.size() + " sprite inclusions: " + indexPath);
            }
        } catch (IOException e) {
            System.err.println("Error generating index file: " + e.getMessage());
        }
    }
}