package fiji.plugin.trackmate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;

import fiji.plugin.trackmate.features.edges.EdgeAnalyzer;
import fiji.plugin.trackmate.features.track.TrackAnalyzer;
import fiji.plugin.trackmate.visualization.TrackMateModelView;

/**
 * <h1>The model for the data managed by TrackMate plugin.</h1>
 * <p>
 * This is a relatively large class, with a lot of public methods. This
 * complexity arose because this class handles data storage and manipulation,
 * through user manual editing and automatic processing. To avoid conflicting
 * accesses to the data, some specialized methods had to be created, hopefully
 * built in coherent sets.
 * 
 * <h2>Main data stored in this model</h2>
 * 
 * We only list here the central fields. This model has other fields, but they
 * are derived from these following 6 fields or used to modify them. These are
 * the only 6 fields that should be written to a file, and they should be enough
 * to fully reconstruct a new model. By processing order, this model stores the
 * following data.
 * 
 * <h3> {@link #settings}</h3>
 * 
 * The {@link Settings} object that determines the behavior of processes,
 * generating the data stored by this model.
 * 
 * <h3>{@link #spots}</h3>
 * 
 * The raw spots generated by the detection process, stored as
 * {@link SpotCollection}.
 * 
 * <h3>{@link #initialSpotFilterValue}</h3>
 * 
 * The value of the initial Spot {@link FeatureFilter} on
 * {@link SpotFeature#QUALITY}. Since this filter is constrained to be on
 * quality, and above threshold, we do not store the filter itself (preventing
 * some nasty modifications to be possible), but simply the value of the
 * threshold. That filter will be used to crop the {@link #spots} field: spots
 * with quality lower than this threshold will be removed from the
 * {@link SpotCollection}. They will not be stored, nor saved, nor displayed and
 * their features will not be calculated. This is intended to save computation
 * time only.
 * 
 * <h3>{@link #spotFilters}</h3>
 * 
 * The list of Spot {@link FeatureFilter} that the user can set on any computed
 * feature. It will be used to filter spots and generate the
 * {@link #spotSelection} field, that will be used for tracking.
 * <p>
 * Since it only serves to determine the effect of a process (filtering spots by
 * feature), it logically could be a sub-field of the {@link #settings} object.
 * We found more convenient to have it attached to the model.
 * 
 * <h3>{@link #spotSelection}</h3>
 * 
 * The filtered spot, as a new {@link SpotCollection}. It is important that this
 * collection is made with the same spot objects than for the {@link #spots} field.
 * 
 * <h3>{@link #graph}</h3>
 * 
 * The {@link SimpleDirectedWeightedGraph} that contains the map of links between spots.
 * The vertices of the graph are the content of the {@link #spotSelection}
 * field. This is the only convenient way to store links in their more general
 * way we have thought of.
 * <p>
 * It is an undirected graph: we do not indicate the time forward direction
 * using edge direction, but simply refer to the per-frame organization of the
 * {@link SpotCollection}.
 * 
 * <h3>{@link #filteredTrackKeys}</h3>
 * 
 * This Set contains the index of the tracks that are set to be visible. We use this 
 * to flag the tracks that should be retained after filtering the tracks by their
 * features, for instance. Because the user can edit this manually, or because 
 * the track visibility can changed when merging 2 track manually (for instance),
 * we stress on the 'visibility' meaning of this field. 
 * <p>
 * The set contains the indices of the tracks that are visible, in the List
 * of {@link #trackEdges} and {@link #trackSpots}, that are described below.
 * These fields are generated automatically from the track {@link #graph}.
 * For instance, if this set is made of [2, 4], that means the tracks with
 * the indices 2 and 4 in the aforementioned lists are visible, the other not.
 * Of course, {@link TrackMateModelView}s are expected to acknowledge this 
 * content. 
 * <p>
 * This field can be modified publicly using the  {@link #setTrackVisible(Integer, boolean, boolean)}
 * method, or totally overwritten using the {@link #setFilteredTrackIDs(Set, boolean)} method.
 * However, some modifications can arise coming from manual editing of tracks. For instance
 * removing an edge from the middle of a visible tracks generates two new tracks, and
 * possibly shifts the indices of the other tracks. This is hopefully taken care of 
 * the model internal work, and the following rules are implements:
 * <ul>
 * 	<li> TODO
 * </ul>   
 * 
 * <h2>Dependent data</h2>
 * 
 * We list here the fields whose value depends on 
 * 
 * @author Jean-Yves Tinevez <tinevez@pasteur.fr> - 2010-2011
 * 
 */
public class TrackMateModel {

	/*
	 * CONSTANTS
	 */

	private static final boolean DEBUG = false;

	/*
	 * FIELDS
	 */

	// FEATURES

	private final FeatureModel featureModel;

	// TRACKS

	private final TrackGraphModel trackGraphModel;

	// SPOTS

	/** Contain the detection result, un-filtered. */
	protected SpotCollection spots = new SpotCollection();
	/** Contain the spots retained for tracking, after filtering by features. */
	protected SpotCollection filteredSpots = new SpotCollection();


	// TRANSACTION MODEL

	/**
	 * Counter for the depth of nested transactions. Each call to beginUpdate
	 * increments this counter and each call to endUpdate decrements it. When
	 * the counter reaches 0, the transaction is closed and the respective
	 * events are fired. Initial value is 0.
	 */
	private int updateLevel = 0;
	private HashSet<Spot> spotsAdded = new HashSet<Spot>();
	private HashSet<Spot> spotsRemoved = new HashSet<Spot>();
	private HashSet<Spot> spotsMoved = new HashSet<Spot>();
	private HashSet<Spot> spotsUpdated = new HashSet<Spot>();
	/**
	 * The event cache. During a transaction, some modifications might trigger
	 * the need to fire a model change event. We want to fire these events only
	 * when the transaction closes (when the updayeLevel reaches 0), so we store
	 * the event ID in this cache in the meantime. The event cache contains only
	 * the int IDs of the events listed in {@link TrackMateModelChangeEvent},
	 * namely
	 * <ul>
	 * <li> {@link TrackMateModelChangeEvent#SPOTS_COMPUTED}
	 * <li> {@link TrackMateModelChangeEvent#SPOT_FILTERED}
	 * <li> {@link TrackMateModelChangeEvent#TRACKS_COMPUTED}
	 * <li> {@link TrackMateModelChangeEvent#TRACKS_VISIBILITY_CHANGED}
	 * </ul>
	 * The {@link TrackMateModelChangeEvent#MODEL_MODIFIED} cannot be cached
	 * this way, for it needs to be configured with modification spot and edge
	 * targets, so it uses a different system (see {@link #flushUpdate()}).
	 */
	private HashSet<Integer> eventCache = new HashSet<Integer>();

	// SELECTION

	private final SelectionModel selectionModel;

	// OTHERS

	/** The logger to append processes messages */
	private Logger logger = Logger.DEFAULT_LOGGER;
	/** The settings that determine processes actions */
	private Settings settings = new Settings();

	// LISTENERS

	/**
	 * The list of listeners listening to model content change, that is, changes
	 * in {@link #spots}, {@link #filteredSpots} and {@link #trackGraph}.
	 */
	List<TrackMateModelChangeListener> modelChangeListeners = new ArrayList<TrackMateModelChangeListener>();





	/*
	 * CONSTRUCTOR
	 */

	public TrackMateModel() {
		featureModel = new FeatureModel(this);
		trackGraphModel = new TrackGraphModel(this);
		selectionModel = new SelectionModel(this);
	}


	/*
	 * UTILS METHODS
	 */

	@Override
	public String toString() {
		StringBuilder str = new StringBuilder();

		str.append(settings);

		str.append('\n');
		if (null == spots || spots.size() == 0) {
			str.append("No spots.\n");
		} else {
			str.append("Contains " + spots.getNSpots() + " spots in total.\n");
		}
		if (null == spots || spots.size() == 0) {
			str.append("No filtered spots.\n");
		} else {
			str.append("Contains " + filteredSpots.getNSpots() + " filtered spots.\n");
		}

		str.append('\n');
		if (trackGraphModel.getNTracks() == 0) {
			str.append("No tracks.\n");
		} else {
			str.append("Contains " + trackGraphModel.getNTracks() + " tracks in total.\n");
		}
		if (trackGraphModel.getNFilteredTracks() == 0) {
			str.append("No filtered tracks.\n");
		} else {
			str.append("Contains " + trackGraphModel.getNFilteredTracks() + " filtered tracks.\n");
		}

		return str.toString();
	}



	/*
	 * DEAL WITH MODEL CHANGE LISTENER
	 */

	public void addTrackMateModelChangeListener(TrackMateModelChangeListener listener) {
		modelChangeListeners.add(listener);
	}

	public boolean removeTrackMateModelChangeListener(TrackMateModelChangeListener listener) {
		return modelChangeListeners.remove(listener);
	}

	public List<TrackMateModelChangeListener> getTrackMateModelChangeListener(TrackMateModelChangeListener listener) {
		return modelChangeListeners;
	}

	/*
	 * DEAL WITH SELECTION CHANGE LISTENER
	 */

	public boolean addTrackMateSelectionChangeListener(TrackMateSelectionChangeListener listener) {
		return selectionModel.addTrackMateSelectionChangeListener(listener);
	}

	public boolean removeTrackMateSelectionChangeListener(TrackMateSelectionChangeListener listener) {
		return selectionModel.removeTrackMateSelectionChangeListener(listener);
	}

	public List<TrackMateSelectionChangeListener> getTrackMateSelectionChangeListener() {
		return selectionModel.getTrackMateSelectionChangeListener();
	}

	/*
	 * GRAPH MODIFICATION
	 */

	public void beginUpdate() {
		updateLevel++;
		if (DEBUG)
			System.out.println("[TrackMateModel] #beginUpdate: increasing update level to " + updateLevel + ".");
	}

	public void endUpdate() {
		updateLevel--;
		if (DEBUG)
			System.out.println("[TrackMateModel] #endUpdate: decreasing update level to " + updateLevel + ".");
		if (updateLevel == 0) {
			if (DEBUG)
				System.out.println("[TrackMateModel] #endUpdate: update level is 0, calling flushUpdate().");
			flushUpdate();
		}
	}

	/*
	 * TRACK METHODS: WE DELEGATE TO THE TRACK GRAPH MODEL
	 */

	/**
	 * @return the {@link TrackGraphModel} that manages tracks for this model.
	 */
	public TrackGraphModel getTrackModel() {
		return trackGraphModel;
	}

	/*
	 * GETTERS / SETTERS FOR SPOTS
	 */


	/**
	 * @return the spots generated by the detection part of this plugin. The
	 * collection are un-filtered and contain all spots. They are returned as a
	 * {@link SpotCollection}.
	 */
	public SpotCollection getSpots() {
		return spots;
	}

	/**
	 * Return the spots filtered by feature filters. These spots will be used
	 * for subsequent tracking and display.
	 * <p>
	 * Feature thresholds can be set / added / cleared by
	 * {@link #setSpotFilters(List)}, {@link #addSpotFilter(SpotFilter)} and
	 * {@link #clearSpotFilters()}.
	 */
	public SpotCollection getFilteredSpots() {
		return filteredSpots;
	}

	/**
	 * Overwrite the raw {@link #spots} field, resulting normally from the
	 * {@link #execDetection()} process.
	 * 
	 * @param spots
	 * @param doNotify
	 *            if true, will file a
	 *            {@link TrackMateModelChangeEvent#SPOTS_COMPUTED} event.
	 */
	public void setSpots(SpotCollection spots, boolean doNotify) {
		this.spots = spots;
		if (doNotify) {
			final TrackMateModelChangeEvent event = new TrackMateModelChangeEvent(this, TrackMateModelChangeEvent.SPOTS_COMPUTED);
			for (TrackMateModelChangeListener listener : modelChangeListeners)
				listener.modelChanged(event);
		}
	}

	/**
	 * Overwrite the {@link #filteredSpots} field, resulting normally from the
	 * {@link #execSpotFiltering()} process.
	 * 
	 * @param doNotify  if true, will fire a {@link TrackMateModelChangeEvent#SPOTS_FILTERED} event.
	 */
	public void setFilteredSpots(final SpotCollection filteredSpots, boolean doNotify) {
		this.filteredSpots = filteredSpots;
		if (doNotify) {
			final TrackMateModelChangeEvent event = new TrackMateModelChangeEvent(this, TrackMateModelChangeEvent.SPOTS_FILTERED);
			for (TrackMateModelChangeListener listener : modelChangeListeners)
				listener.modelChanged(event);
		}
	}

	/*
	 * LOGGER
	 */

	/**
	 * Set the logger that will receive the messages from the processes
	 * occurring within this plugin.
	 */
	public void setLogger(Logger logger) {
		this.logger = logger;
	}

	/**
	 * Return the logger currently set for this model.
	 */
	public Logger getLogger() {
		return logger;
	}

	/*
	 * SETTINGS
	 */

	/**
	 * Return the {@link Settings} object that determines the behavior of this plugin.
	 */
	public Settings getSettings() {
		return settings;
	}

	/**
	 * Set the {@link Settings} object that determines the behavior of this
	 * model's processes.
	 * 
	 * @see #execDetection()
	 * @see #execTracking()
	 */

	public void setSettings(Settings settings) {
		this.settings = settings;
	}

	/*
	 * FEATURES
	 */

	public FeatureModel getFeatureModel() {
		return featureModel;
	}

	/*
	 * SELECTION METHODS we delegate to the SelectionModel component
	 */

	public SelectionModel getSelectionModel() {
		return selectionModel;
	}

	/*
	 * MODEL CHANGE METHODS
	 */

	/**
	 * Move a single spot from a frame to another, then mark it for feature update.
	 * 
	 * @param spotToMove  the spot to move
	 * @param fromFrame  the frame the spot originated from
	 * @param toFrame  the destination frame
	 * @param doNotify   if false, {@link TrackMateModelChangeListener}s will not be
	 * notified of this change
	 * @return the spot that was moved
	 */
	public Spot moveSpotFrom(Spot spotToMove, Integer fromFrame, Integer toFrame) {
		spots.add(spotToMove, toFrame);
		spots.remove(spotToMove, fromFrame);
		if (DEBUG)
			System.out.println("[TrackMateModel] Moving " + spotToMove + " from frame " + fromFrame + " to frame " + toFrame);

		filteredSpots.add(spotToMove, toFrame);
		filteredSpots.remove(spotToMove, fromFrame);
		// Mark for update spot and edges
		trackGraphModel.edgesModified.addAll(trackGraphModel.edgesOf(spotToMove));
		spotsMoved.add(spotToMove); 
		return spotToMove;

	}

	/**
	 * Add a single spot to the collections managed by this model, then update
	 * its features.
	 * @return the spot just added
	 */
	public Spot addSpotTo(Spot spotToAdd, Integer toFrame) {
		if (spots.add(spotToAdd, toFrame)) {
			spotsAdded.add(spotToAdd); // TRANSACTION
			if (DEBUG)
				System.out.println("[TrackMateModel] Adding spot " + spotToAdd + " to frame " + toFrame);
		}
		filteredSpots.add(spotToAdd, toFrame);
		trackGraphModel.addSpot(spotToAdd);
		return spotToAdd;
	}

	/**
	 * Remove a single spot from the collections managed by this model.
	 * 
	 * @param fromFrame
	 *            the frame the spot is in, if it is known. If <code>null</code>
	 *            is given, then the adequate frame is retrieved from this
	 *            model's collections.
	 * @return the spot removed
	 */
	public Spot removeSpotFrom(final Spot spotToRemove, Integer fromFrame) {
		if (fromFrame == null)
			fromFrame = spots.getFrame(spotToRemove);
		if (spots.remove(spotToRemove, fromFrame)) {
			spotsRemoved.add(spotToRemove); // TRANSACTION
			if (DEBUG)
				System.out.println("[TrackMateModel] Removing spot " + spotToRemove + " from frame " + fromFrame);
		}
		filteredSpots.remove(spotToRemove, fromFrame);
		selectionModel.removeSpotFromSelection(spotToRemove);
		trackGraphModel.removeSpot(spotToRemove); // changes to edges will be caught automatically by the TrackGraphModel
		return spotToRemove;
	}

	/**
	 * Mark the specified spot for update. At the end of the model transaction, its features 
	 * will be recomputed, and other edge and track features that depends on it will 
	 * be as well.
	 * @param spotToUpdate  the spot to mark for update
	 */
	public void updateFeatures(final Spot spotToUpdate) {
		spotsUpdated.add(spotToUpdate); // Enlist for feature update when transaction is marked as finished
		trackGraphModel.edgesModified.addAll(trackGraphModel.edgesOf(spotToUpdate));
	}

	/**
	 * @see TrackGraphModel#addEdge(Spot, Spot, double)
	 */
	public DefaultWeightedEdge addEdge(final Spot source, final Spot target, final double weight) {
		return trackGraphModel.addEdge(source, target, weight);
	}

	/**
	 * @see TrackGraphModel#removeEdge(Spot, Spot)
	 */
	public DefaultWeightedEdge removeEdge(final Spot source, final Spot target) {
		return trackGraphModel.removeEdge(source, target);
	}

	/**
	 * @see TrackGraphModel#removeEdge(DefaultWeightedEdge)
	 */
	public boolean removeEdge(final DefaultWeightedEdge edge) {
		return trackGraphModel.removeEdge(edge);
	}
	
	/**
	 * @see TrackGraphModel#setEdgeWeight(DefaultWeightedEdge, double)
	 */
	public void setEdgeWeight(final DefaultWeightedEdge edge, double weight) {
		trackGraphModel.setEdgeWeight(edge, weight);
	}


	/*
	 * PRIVATE METHODS
	 */


	/**
	 * Fire events. Regenerate fields derived from the filtered graph.
	 */
	private void flushUpdate() {

		if (DEBUG) {
			System.out.println("[TrackMateModel] #flushUpdate().");
			System.out.println("[TrackMateModel] #flushUpdate(): Event cache is :" + eventCache);
		}

		/* Before they enter void, we grab the trackID of removed edges. We will need to 
		 * know from where they were removed to recompute trackIDs intelligently. Or so */
		HashMap<DefaultWeightedEdge, Integer> edgeRemovedOrigins = new HashMap<DefaultWeightedEdge, Integer>(trackGraphModel.edgesRemoved.size());
		if (trackGraphModel.edgesRemoved.size() > 0) {
			for (DefaultWeightedEdge edge : trackGraphModel.edgesRemoved) {
				edgeRemovedOrigins.put(edge, getTrackModel().getTrackIDOf(edge)); // we store old track IDs
			}
		}

		// Store old track IDs to monitor what tracks are new
		HashSet<Integer> oldTrackIDs = new HashSet<Integer>(trackGraphModel.getTrackIDs());

		/* We recompute tracks only if some edges have been added or removed,
		 * (if some spots have been removed that causes edges to be removes, we already know about it).
		 * We do NOT recompute tracks if spots have been added: they will not result in
		 * new tracks made of single spots.	 */
		int nEdgesToSignal = trackGraphModel.edgesAdded.size() + trackGraphModel.edgesRemoved.size() + trackGraphModel.edgesModified.size();
		if (nEdgesToSignal > 0) {
			// First, regenerate the tracks
			trackGraphModel.computeTracksFromGraph();
		}

		// Do we have new track appearing?
		HashSet<Integer> tracksToUpdate = new HashSet<Integer>(trackGraphModel.getTrackIDs());
		tracksToUpdate.removeAll(oldTrackIDs);

		// We also want to update the tracks that have edges that were modified
		for (DefaultWeightedEdge modifiedEdge : trackGraphModel.edgesModified) {
			tracksToUpdate.add(trackGraphModel.getTrackIDOf(modifiedEdge));
		}

		// Deal with new or moved spots: we need to update their features.
		int nSpotsToUpdate = spotsAdded.size() + spotsMoved.size() + spotsUpdated.size();
		if (nSpotsToUpdate > 0) {
			ArrayList<Spot> spotsToUpdate = new ArrayList<Spot>(nSpotsToUpdate);
			spotsToUpdate.addAll(spotsAdded);
			spotsToUpdate.addAll(spotsMoved);
			spotsToUpdate.addAll(spotsUpdated);
			// Update these spots feaures
			SpotCollection toCompute = filteredSpots.subset(spotsToUpdate);
			featureModel.computeSpotFeatures(toCompute, false);
		}

		// Initialize event
		TrackMateModelChangeEvent event = new TrackMateModelChangeEvent(this, TrackMateModelChangeEvent.MODEL_MODIFIED);

		// Configure it with spots to signal.
		int nSpotsToSignal = nSpotsToUpdate + spotsRemoved.size();
		if (nSpotsToSignal > 0) {
			event.addAllSpots(spotsAdded);
			event.addAllSpots(spotsRemoved);
			event.addAllSpots(spotsMoved);
			event.addAllSpots(spotsUpdated);

			for (Spot spot : spotsAdded) {
				event.putSpotFlag(spot, TrackMateModelChangeEvent.FLAG_SPOT_ADDED);
			}
			for (Spot spot : spotsRemoved) {
				event.putSpotFlag(spot, TrackMateModelChangeEvent.FLAG_SPOT_REMOVED);
			}
			for (Spot spot : spotsMoved) {
				event.putSpotFlag(spot, TrackMateModelChangeEvent.FLAG_SPOT_FRAME_CHANGED);
			}
			for (Spot spot : spotsUpdated) {
				event.putSpotFlag(spot, TrackMateModelChangeEvent.FLAG_SPOT_MODIFIED);
			}
		}


		// Configure it with edges to signal.
		if (nEdgesToSignal > 0) {
			event.addAllEdges(trackGraphModel.edgesAdded);
			event.addAllEdges(trackGraphModel.edgesRemoved);
			event.addAllEdges(trackGraphModel.edgesModified);

			for (DefaultWeightedEdge edge : trackGraphModel.edgesAdded) {
				event.putEdgeFlag(edge, TrackMateModelChangeEvent.FLAG_EDGE_ADDED);
			}
			for (DefaultWeightedEdge edge : trackGraphModel.edgesRemoved) {
				event.putEdgeFlag(edge, TrackMateModelChangeEvent.FLAG_EDGE_REMOVED);
			}
			for (DefaultWeightedEdge edge : trackGraphModel.edgesModified) {
				event.putEdgeFlag(edge, TrackMateModelChangeEvent.FLAG_EDGE_MODIFIED);
			}
		}

		// Configure it with the tracks we found need updating
		event.setTracksUpdated(tracksToUpdate);

		/*
		 * Update features if needed
		 * In this order: edges then tracks (in case track features depend on edge features) 
		 */

		int nEdgesToUpdate = trackGraphModel.edgesAdded.size() + trackGraphModel.edgesModified.size();
		if (nEdgesToUpdate > 0) {
			if (null != featureModel.trackAnalyzerProvider) {
				HashSet<DefaultWeightedEdge> edgesToUpdate =  
						new HashSet<DefaultWeightedEdge>(trackGraphModel.edgesAdded.size() + trackGraphModel.edgesModified.size());
				edgesToUpdate.addAll(trackGraphModel.edgesAdded);
				edgesToUpdate.addAll(trackGraphModel.edgesModified);
				HashSet<DefaultWeightedEdge> globalEdgesToUpdate = null; // for now - compute it only if we need
				
				for (String analyzerKey : featureModel.edgeAnalyzerProvider.getAvailableEdgeFeatureAnalyzers()) {
					EdgeAnalyzer analyzer = featureModel.edgeAnalyzerProvider.getEdgeFeatureAnalyzer(analyzerKey);
					if (analyzer.isLocal()) {
						
						analyzer.process(edgesToUpdate);
						
					} else {
						
						// Get the all the edges of the track they belong to
						if (null == globalEdgesToUpdate) {
							globalEdgesToUpdate = new HashSet<DefaultWeightedEdge>();
							for (DefaultWeightedEdge edge : edgesToUpdate) {
								Integer motherTrackID = trackGraphModel.getTrackIDOf(edge);
								globalEdgesToUpdate.addAll(trackGraphModel.getTrackEdges(motherTrackID));
							}
						}
						analyzer.process(globalEdgesToUpdate);
					}
				}
			}

		}

		/*
		 *  If required, recompute features for new tracks or tracks that 
		 *  have been modified, BEFORE any other listeners to model changes, 
		 *  and that night need to exploit new feature values (e.g. model views).
		 */
		if (nEdgesToSignal > 0) {
			if (null != featureModel.trackAnalyzerProvider) {
				for (String analyzerKey : featureModel.trackAnalyzerProvider.getAvailableTrackFeatureAnalyzers()) {
					TrackAnalyzer analyzer = featureModel.trackAnalyzerProvider.getTrackFeatureAnalyzer(analyzerKey);
					if (analyzer.isLocal()) {
						analyzer.process(tracksToUpdate);
					} else {
						analyzer.process(trackGraphModel.getFilteredTrackIDs());
					}
				}
			}
		}

		try {
			if (nEdgesToSignal + nSpotsToSignal > 0) {
				if (DEBUG) {
					System.out.println("[TrackMateModel] #flushUpdate(): firing model modified event.");
				}
				for (final TrackMateModelChangeListener listener : modelChangeListeners) {
					listener.modelChanged(event);
				}
			}

			// Fire events stored in the event cache
			for (int eventID : eventCache) {
				if (DEBUG) {
					System.out.println("[TrackMateModel] #flushUpdate(): firing event with ID "	+ eventID);
				}
				TrackMateModelChangeEvent cachedEvent = new TrackMateModelChangeEvent(this, eventID);
				for (final TrackMateModelChangeListener listener : modelChangeListeners) {
					listener.modelChanged(cachedEvent);
				}
			}

		} finally {
			spotsAdded.clear();
			spotsRemoved.clear();
			spotsMoved.clear();
			spotsUpdated.clear();
			trackGraphModel.edgesAdded.clear();
			trackGraphModel.edgesRemoved.clear();
			trackGraphModel.edgesModified.clear();
			eventCache.clear();
		}

	}



}
