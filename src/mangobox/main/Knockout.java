/*
 * Written by Liam Davies 2018.  
 * Feel free to redistribute or modify this code to your hearts content,
 * but please be a sick lad and credit me.
 * 
 * My GitHub: https://github.com/MangoBox
 */

package mangobox.main;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public class Knockout extends JavaPlugin implements Listener {
	
	//Constants. Feel free to change these and re-export. I'll put these into a config if I can be bothered.
	public static final int TICKS_KNOCKOUT_DAMAGE = 20;
	public static final double REVIVE_DIST = 3D;
	public static final float MIN_REVIVE_PITCH = 70f;
	public static final float INCR_REVIVE_SPEED = 0.005f;
	
	//Self reference.
	public static Knockout ko;
	public void onEnable() {
		getLogger().info("BattleRoyaleKnockout by MangoBox is enabling...");
		ko = this;
		getServer().getPluginManager().registerEvents(this, this);
		
		ItemStack bandage = getBandageItem();
		ShapelessRecipe bdgRecipe = new ShapelessRecipe(new NamespacedKey(this, "knockout_bandage"), bandage);
		bdgRecipe.addIngredient(Material.GHAST_TEAR);
		bdgRecipe.addIngredient(Material.PAPER);
		bdgRecipe.addIngredient(Material.SPECKLED_MELON);
		getServer().addRecipe(bdgRecipe);
		startTasks();
		
		getLogger().info("BattleRoyaleKnockout by MangoBox has been enabled!");
	}
	
	public static ArrayList<Player> knockedOutPlayers = new ArrayList<Player>();
	//Reviver, Reviving.
	public static HashMap<Player, Player> revivingPlayers = new HashMap<Player, Player>();
	//Reviving, Progress.
	public static HashMap<Player, Float> revivingProgress = new HashMap<Player, Float>();
	//Array containing players that have left. Key is player UUID, Double is leaving health.
	public static HashMap<UUID, Double> quitKnockedOutPlayers = new HashMap<UUID, Double>();
	
	//Starts repeating tasks, one with 1 tick delay and another with 60. Do NOT call after plugin init.
	public void startTasks() {
		new BukkitRunnable() {
			@Override
			public void run() {
				if(knockedOutPlayers.size() != 0) {
					for(Player p: knockedOutPlayers) {
						if(!revivingPlayers.containsValue(p)) {
							p.sendTitle("§cKnocked Out", "§4Find someone to revive you!", 0, 3, 0);
						}
						p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 3, 2, true, true), true);
						p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 5, 2, true, true), true);
						p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 5, 4, true, true), true);
						p.getWorld().spawnParticle(Particle.HEART, p.getLocation(), 1, 0.75f, 0.75f, 0.75f);
					}
				}
				
				if(revivingPlayers.size() > 0) {
					//Using out-of-loop array to prevent ConcurrentModificationExceptions.
					ArrayList<Map.Entry<Player, Player>> toRemove = new ArrayList<Map.Entry<Player,Player>>();
					for(Map.Entry<Player, Player> entry : revivingPlayers.entrySet()) {
						//Reviver
						Player r = entry.getKey();
						//Knocked out
						Player v = entry.getValue();
						float perc = revivingProgress.get(v);
						String revPerString = revivePercentage(perc);
						v.sendTitle("§a" + revPerString, "§a" + r.getDisplayName() + " is reviving you...", 0, 3, 0);
						r.sendTitle("", "§aReviving " + v.getDisplayName() + " (" + revPerString + ")", 0, 3, 0);
						if(!canRevive(r, v)) {
							toRemove.add(entry);
							continue;
						}
						revivingProgress.put(v, INCR_REVIVE_SPEED + revivingProgress.get(entry.getValue()));
						if(perc > 1f) {
							revivePlayer(r, v);
						}
					}	
					for(Map.Entry<Player, Player> e : toRemove) {
						revivingPlayers.remove(e.getKey());
						revivingProgress.remove(e.getValue());
					}
				}
			}
			
		}.runTaskTimer(this, 1, 1);
		
		new BukkitRunnable() {
			@Override
			public void run() {
					if(knockedOutPlayers.size() != 0) {
						ArrayList<Player> kill = new ArrayList<Player>();
						for(Player p : knockedOutPlayers) {
							if(p.getHealth() > 0) {
								if(!revivingProgress.containsKey(p)) {
									p.setHealth(Math.max(p.getHealth() - 1d, 0));	
								}
								
								//Blindness needs to be applied outside of a fast-firing timer as its effect fades in.
								p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0, true, true), true);
							} else {
								kill.add(p);
							}
						}
						for(Player p : kill) {
							killKnockedOutPlayer(p);
						}
					}
			}
		}.runTaskTimer(this, 1, 60);
		
	}
	
	@EventHandler
	public static void playerMoveEvent(PlayerMoveEvent e) {
		Player p = e.getPlayer();
		//Checks the player is actually ready to revive. More resource efficient than going into the Entity loop below.
		if(p.getLocation().getPitch() < MIN_REVIVE_PITCH) {
			return;
		}
		
		List<Entity> entities = p.getNearbyEntities(10, 10, 10);
		for(Entity ent : entities) {
			if(!(ent instanceof Player)) {
				break;
			}
			
			Player v = (Player) ent;

			if(!canRevive(p, v)) {
				revivingPlayers.remove(p);
				revivingProgress.remove(v);

				return;
			}
			
			if(!revivingPlayers.containsKey(p)) {
				revivingPlayers.put(p, v);
				revivingProgress.put(v, 0f);
			}
		}
	}
	
	@EventHandler
	public static void playerInteractAtEntityEvent(PlayerInteractAtEntityEvent e) {
		Player p = e.getPlayer();
		if(e.getRightClicked() == null) return;

		if(e.getRightClicked() instanceof Player) {
			ItemStack inHand = p.getItemInHand().clone();
			//We need to set the amount to one as the player may have more than one bandage.
			inHand.setAmount(1);
			if(!inHand.equals(getBandageItem())) {
				return;
			}
			Player v = (Player) e.getRightClicked();
			if(isKnockedOut(p) || !isKnockedOut(v)) {
				return;
			}
			revivePlayer(p, v);
			p.getInventory().removeItem(getBandageItem());
		} 
	}	
	
	public static boolean canRevive(Player p, Player v) {
		if(p.getLocation().getPitch() < MIN_REVIVE_PITCH) {
			return false;
		}
		
		//Checks the reviver is not knocked out and the knocked out is actually knocked out.
		if(isKnockedOut(p) || !isKnockedOut(v)) {
			return false;
		}
		
		if(p.getLocation().distance(v.getLocation()) > REVIVE_DIST) {
			return false;
		}
		
		return true;
	}
	
	@EventHandler
	public static void onEntityDamageEvent(EntityDamageEvent e) {
		if(!(e.getEntity() instanceof Player)) {
			return;
		}
		Player p = (Player) e.getEntity();
		//We aren't interested in re-knocking out players.
		if(knockedOutPlayers.contains(p)) {
			return;
		}
		if(e.getDamage() >= p.getHealth()) {
			e.setCancelled(true);
			knockoutPlayer(p, true);
		}
	}
	
	@EventHandler
	public static void playerQuitEvent(PlayerQuitEvent e) {
		if(isKnockedOut(e.getPlayer())) {
			quitKnockedOutPlayers.put(e.getPlayer().getUniqueId(), e.getPlayer().getHealth());
			unknockoutPlayer(e.getPlayer());
		}
	}
	
	@EventHandler
	public static void playerJoinEvent(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		if(quitKnockedOutPlayers.containsKey(p.getUniqueId())) {
			knockoutPlayer(p, false);
			p.setHealth(quitKnockedOutPlayers.get(p.getUniqueId()));
			quitKnockedOutPlayers.remove(p.getUniqueId());
		}
	}
	
	@EventHandler
	public static void playerInteractEvent(PlayerInteractEvent e) {
		if(isKnockedOut(e.getPlayer())) {
			e.setCancelled(true);
		}
	}
	
	public static void revivePlayer(Player r, Player v) {
		r.sendMessage("§aYou have revived " + v.getDisplayName() + "!");
		v.getWorld().spawnParticle(Particle.TOTEM, v.getLocation(), 125, 0.75f, 0.75f, 0.75f);
		v.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 160, 1,false, true), true);
		v.sendMessage("§aYou have been revived!");
		unknockoutPlayer(v);
	}
	
	public static void unknockoutPlayer(Player p) {
		if(knockedOutPlayers.contains(p)) {
			knockedOutPlayers.remove(p);	
		}
		if(revivingPlayers.containsValue(p)) {
			for(Map.Entry<Player, Player> entry : revivingPlayers.entrySet()) {
				if(entry.getValue() == p)  {
					revivingPlayers.remove(entry.getKey());
				}
			}
		}
		if(revivingProgress.containsKey(p)) {
			revivingProgress.remove(p);	
		}
		
	}
	
	//WasDamage parameter states whether the knockout was because of damage - we sometimes need to knock out players if they log back in.
	public static void knockoutPlayer(Player p, boolean wasDamage) {
		if(wasDamage) {
			ko.getServer().broadcastMessage(p.getDisplayName() + " was knocked out");
			p.setHealth(p.getMaxHealth());
			p.getWorld().spawnParticle(Particle.SPELL_INSTANT, p.getLocation().add(new Location(p.getWorld(), 0f,1.25f,0f)), 35, 0.25f, 0.25f, 0.25f);
		}
		
		knockedOutPlayers.add(p);
		
	}
	
	public static boolean isKnockedOut(Player p) {
		return knockedOutPlayers.contains(p);
	}
	
	public static void killKnockedOutPlayer(Player p) {
		p.setHealth(0);
		unknockoutPlayer(p);
	}
	
	public static String revivePercentage(float amount) {
		return String.valueOf((int)(amount * 100f / 1.0f)) + "%";
	}
	
	public static ItemStack getBandageItem() {
		ItemStack bandage = new ItemStack(Material.PAPER);
		ItemMeta bandageMeta = bandage.getItemMeta();
		bandageMeta.setDisplayName("§aBandage");
		List<String> lores = new ArrayList<String>();
		lores.add("§fRight click on a knocked-out");
		lores.add("§fplayer to revive them instantly!");
		bandageMeta.setLore(lores);
		bandageMeta.addEnchant(Enchantment.ARROW_FIRE,0,true);
		bandageMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
		bandage.setItemMeta(bandageMeta);
		return bandage;
	}
	
}
