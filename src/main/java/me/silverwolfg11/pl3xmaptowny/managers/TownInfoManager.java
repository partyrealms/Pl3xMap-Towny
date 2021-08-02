/*
 * Copyright (c) 2021 Silverwolfg11
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package me.silverwolfg11.pl3xmaptowny.managers;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownyFormatter;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.object.Government;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownyObject;
import me.silverwolfg11.pl3xmaptowny.objects.TwoPair;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// This class handles loading the tooltip HTML files as well as filling in the placeholders
public class TownInfoManager {

    private final List<TwoPair<String, Function<Town, String>>> clickReplacements = new ArrayList<>();
    private final List<TwoPair<String, Function<Town, String>>> hoverReplacements = new ArrayList<>();

    private String clickWindowTxt;
    private String hoverWindowTxt;

    // Used for %founded% replacements.
    private final SimpleDateFormat registeredTimeFormat =  new SimpleDateFormat("MMM d yyyy");

    public TownInfoManager(File dataFolder, Logger errorLogger) {
        final String CLICK_FILE_NAME = "click_tooltip.html";
        final String HOVER_FILE_NAME = "hover_tooltip.html";

        if (!dataFolder.exists())
            dataFolder.mkdir();

        // Read hover and click files
        File clickWindowFile = new File(dataFolder, CLICK_FILE_NAME);
        if (!clickWindowFile.exists())
            copyFile(errorLogger, CLICK_FILE_NAME, clickWindowFile);

        try {
            clickWindowTxt = readFile(clickWindowFile);
        } catch (IOException e) {
            errorLogger.log(Level.SEVERE, "Unable to read file " + CLICK_FILE_NAME, e);
        }

        if (clickWindowTxt != null)
            clickWindowTxt = removeComments(clickWindowTxt);

        File hoverWindowFile = new File(dataFolder, HOVER_FILE_NAME);
        if (!hoverWindowFile.exists())
            copyFile(errorLogger, HOVER_FILE_NAME, hoverWindowFile);

        try {
            hoverWindowTxt = readFile(hoverWindowFile);
        } catch (IOException e) {
            errorLogger.log(Level.SEVERE, "Unable to read file " + HOVER_FILE_NAME, e);
        }

        if (hoverWindowTxt != null)
            hoverWindowTxt = removeComments(hoverWindowTxt);

        registerReplacements();
    }

    // Replacement Registration Methods

    private void registerReplacements() {
        register("town", TownyObject::getName);
        register("mayor", t -> {
            if (t.getMayor() != null) {
                return t.getMayor().getName();
            } else {
                return "";
            }
        });
        register("firespread", t -> String.valueOf(t.getPermissions().fire));
        register("pvp", t -> String.valueOf(t.getPermissions().pvp));
        register("explosion", t -> String.valueOf(t.getPermissions().explosion));
        register("mobs", t -> String.valueOf(t.getPermissions().mobs));
        register("residents", t -> {
            String residents = t.getResidents().stream().map(TownyObject::getName).collect(Collectors.joining(", "));
            return residents.isEmpty() ? "None" : residents;
        });
        register("nation-role", t -> {
            String nationRole = "";
            String nation = "";
            try {
                if(t.hasNation()) {
                    nation = t.getNation().getName();
                }
            } catch (NotRegisteredException e) {
                e.printStackTrace();
            }
            if (t.isCapital()) {
                nationRole = "Capital of " + nation;
            } else if (t.hasNation()) {
                nationRole = "Member of " + nation;
            }
            return nationRole;
        });
        register("tax", t -> {
            if (!t.isTaxPercentage()) {
                return "$" + t.getTaxes();
            } else {
                return t.getTaxes() + "%";
            }
        });
        register("resident-count", t -> String.valueOf(t.getResidents().size()));
        register("founded", t -> t.getRegistered() != 0 ? TownyFormatter.registeredFormat.format(t.getRegistered()) : "?");
        register("board", Government::getBoard);
        register("bank", t -> "$" + t.getAccount().getCachedBalance());
        register("upkeep", t -> "$" + TownySettings.getTownUpkeepCost(t));
        registerParenthesesReplacement("nation",
                t-> t.hasNation() ? TownyAPI.getInstance().getTownNationOrNull(t).getName() : ""
        );

        registerRanks();
    }

    private void registerParenthesesReplacement(String key, Function<Town, String> func) {
        String wrappedKey = "%" + key + "%";
        String pKey = "(" + wrappedKey + ")";
        Function<Town, String> pFunc = t -> {
            String result = func.apply(t);
            return (result != null && !result.isEmpty()) ? "(" + result + ")" : "";
        };

        if (!register(clickWindowTxt, clickReplacements, pKey, pFunc))
            register(clickWindowTxt, clickReplacements, wrappedKey, func);


        if (!register(hoverWindowTxt, hoverReplacements, pKey, pFunc))
            register(hoverWindowTxt, hoverReplacements, wrappedKey, func);
    }

    private void register(String key, Function<Town, String> func) {
        String wrappedKey = "%" + key + "%";
        register(clickWindowTxt, clickReplacements, wrappedKey, func);
        register(hoverWindowTxt, hoverReplacements, wrappedKey, func);
    }


    // API Methods

    public void registerReplacement(String key, Function<Town, String> func) {
        register(clickWindowTxt, clickReplacements, key, func);
        register(hoverWindowTxt, hoverReplacements, key, func);
    }

    public void unregisterReplacement(String key) {
        clickReplacements.removeIf(p -> p.hasFirst() && p.getFirst().equals(key));
        hoverReplacements.removeIf(p -> p.hasFirst() && p.getFirst().equals(key));
    }

    // ====

    private boolean register(String text, List<TwoPair<String, Function<Town, String>>> replacementList,
                          String key, Function<Town, String> func) {

        if (text != null && text.contains(key)) {
            replacementList.add(TwoPair.of(key, func));
            return true;
        }

        return false;
    }

    // Does not validate ranks.
    private void registerRanks() {
        Pattern pattern = Pattern.compile("%rank_.+%");
        for (int i = 0; i < 2; ++i) {
            String text = i == 0 ? clickWindowTxt : hoverWindowTxt;

            if (text == null)
                continue;

            List<TwoPair<String, Function<Town, String>>> replacementList = i == 0 ? clickReplacements : hoverReplacements;

            Matcher matcher = pattern.matcher(text);

            while (matcher.find()) {
                String rankReplacement = text.substring(matcher.start(), matcher.end());
                // %rank_test% Just need test part. So substring at 5.
                String rank = rankReplacement.substring(5);
                replacementList.add(TwoPair.of(rankReplacement, t -> {
                   String rankResidents = t.getRank(rank).stream().map(TownyObject::getName).collect(Collectors.joining(", "));
                   return rankResidents.isEmpty() ? "None" : rankResidents;
                }));
            }
        }
    }

    public String getClickTooltip(Town town, Logger errorLogger) {
        return getWindowHTML(clickWindowTxt, clickReplacements, town, errorLogger);
    }

    public String getHoverTooltip(Town town, Logger errorLogger) {
        return getWindowHTML(hoverWindowTxt, hoverReplacements, town, errorLogger);
    }

    private String getWindowHTML(String text, List<TwoPair<String, Function<Town, String>>> replacements, Town town, Logger errorLogger) {
        if (text == null || text.isEmpty())
            return "";

        for (TwoPair<String, Function<Town, String>> replacement : replacements) {
            String replacementKey = replacement.getFirst();
            String applied;
            // Yes, it's bad to catch general exceptions.
            // However, if there were any expected exceptions, it wouldn't be called an exception.
            try {
                applied = replacement.getSecond().apply(town);
            } catch (Exception e) {
                errorLogger.log(
                        Level.SEVERE,
                        String.format("Error applying the replacement %s for information on town %s!", replacementKey, town),
                        e
                );
                applied = "[Error]";
            }

            // Replacements are allowed to return a null value.
            if (applied == null)
                applied = "";

            // Use replace because we want to match exactly, not based on regex.
            text = text.replace(replacementKey, applied);
        }

        return text;
    }


    // Utility Methods


    private void copyFile(Logger errorLogger, String resource, File dest) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(resource);

        if (input == null) {
            errorLogger.severe("The resource file '"  + resource + "' could not be found!");
            return;
        }

        try {
            Files.copy(input, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            errorLogger.log(Level.SEVERE, "Unable to copy " + resource + " to plugin directory!");
        }
    }

    // Removes HTML comments from a string.
    private static String removeComments(String htmlStr) {
        return Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(htmlStr).replaceAll("");
    }

    // Read file as string
    // https://stackoverflow.com/questions/326390/how-do-i-create-a-java-string-from-the-contents-of-a-file
    private static String readFile(File file)
            throws IOException {
        byte[] encoded = Files.readAllBytes(file.toPath());
        return new String(encoded, StandardCharsets.UTF_8);
    }

}