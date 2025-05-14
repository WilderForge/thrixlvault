package com.wildermods.thrixlvault.programs;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.Chrysalis;
import com.wildermods.thrixlvault.Vault;
import com.wildermods.thrixlvault.steam.IDownloadable;
import com.wildermods.thrixlvault.utils.OS;
import com.wildermods.thrixlvault.wildermyth.WildermythManifest;

public class VerifyCurrentAndOld {

	public static void main(String[] args) throws IntegrityException, IOException {
		Collection<IDownloadable> manifests = WildermythManifest.manifestStream()
			.filter(WildermythManifest::isPublic)
			.collect(Collectors.toList());
		
		HashSet<IDownloadable> differences = new HashSet<IDownloadable>();
		
		for(IDownloadable manifest : manifests) {
			try {
				Vault currentVault = new Vault(Vault.DEFAULT_VAULT_DIR, OS.fromDepot(manifest));
				Vault oldVault = new Vault(Vault.DEFAULT_VAULT_DIR.resolve("OLD"), OS.fromDepot(manifest));
				
				Chrysalis currentManifest = currentVault.chrysalisize(manifest).getChrysalis();
				Chrysalis oldManifest = oldVault.chrysalisize(manifest).getChrysalis();
				
				System.out.println("=====================" + manifest + "=====================");
				if(printMultimapDifferences(currentManifest.blobs(), oldManifest.blobs(), "Current", "Old")) {
					differences.add(manifest);
				}
			}
			catch(Throwable t) {
				System.err.println("Error processing manifest " + manifest);
				t.printStackTrace();
			}
		}
		
		for(IDownloadable manifest : differences) {
			System.err.println(manifest + " is different");
		}
		
	}
	
    public static <K, V> boolean printMultimapDifferences(SetMultimap<K, V> left, SetMultimap<K, V> right, String leftName, String rightName) {
        boolean different = false;
    	
    	System.out.println("Keys only in " + leftName);
        SetView<K> leftDifference = Sets.difference(left.keySet(), right.keySet());
        if(!different) {
        	different = !leftDifference.isEmpty();
        }
        leftDifference.forEach(k -> {
            System.out.println("Key only in " + leftName + ": " + k + " -> " + left.get(k));
        });

        System.out.println("Keys only in " + rightName);
        SetView<K> rightDifference = Sets.difference(right.keySet(), left.keySet());
        if(!different) {
        	different = !rightDifference.isEmpty();
        }
        rightDifference.forEach(k -> {
            System.out.println("Key only in " + rightName + ": " + k + " -> " + right.get(k));
        });

        System.out.println("Differing values under shared key");
        Sets.intersection(left.keySet(), right.keySet()).forEach(k -> {
            Set<V> leftVals = left.get(k);
            Set<V> rightVals = right.get(k);
            Set<V> onlyInLeft = Sets.difference(leftVals, rightVals);
            Set<V> onlyInRight = Sets.difference(rightVals, leftVals);
            if (!onlyInLeft.isEmpty() || !onlyInRight.isEmpty()) {
                System.out.println("Key: " + k);
                if (!onlyInLeft.isEmpty()) {
                    System.out.println("  Only in " + leftName + ": " + onlyInLeft);
                }
                if (!onlyInRight.isEmpty()) {
                    System.out.println("  Only in " + rightName + ": " + onlyInRight);
                }
            }
        });
        return different;
    }
}

