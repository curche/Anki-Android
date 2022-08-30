/****************************************************************************************
 * Copyright (c) 2011 Norbert Nagold <norbert.nagold@gmail.com>                         *
 * Copyright (c) 2012 Kostas Spyropoulos <inigo.aldana@gmail.com>                       *
 * Copyright (c) 2013 Houssam Salem <houssam.salem.au@gmail.com>                        *
 * Copyright (c) 2018 Chris Williams <chris@chrispwill.com>                             *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General private License as published by the Free Software       *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General private License for more details.            *
 *                                                                                      *
 * You should have received a copy of the GNU General private License along with        *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.libanki.sched;

import android.app.Activity;

import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteConstraintException;
import android.text.TextUtils;
import android.util.Pair;

import com.ichi2.async.CancelListener;
import com.ichi2.async.CollectionTask;
import com.ichi2.async.TaskManager;
import com.ichi2.libanki.Card;
import com.ichi2.libanki.Collection;
import com.ichi2.libanki.Consts;
import com.ichi2.libanki.Decks;
import com.ichi2.libanki.Note;
import com.ichi2.libanki.SortOrder;
import com.ichi2.libanki.Utils;
import com.ichi2.libanki.Deck;
import com.ichi2.libanki.DeckConfig;

import com.ichi2.libanki.utils.Time;
import com.ichi2.libanki.utils.TimeManager;
import com.ichi2.utils.Assert;
import com.ichi2.utils.HashUtil;
import com.ichi2.utils.JSONArray;
import com.ichi2.utils.JSONException;
import com.ichi2.utils.JSONObject;
import com.ichi2.utils.SyncStatus;

import net.ankiweb.rsdroid.BackendFactory;
import net.ankiweb.rsdroid.RustCleanup;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Collectors;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import anki.scheduler.SchedTimingTodayResponse;
import timber.log.Timber;

import static com.ichi2.async.CancelListener.isCancelled;
import static com.ichi2.libanki.sched.BaseSched.UnburyType.*;
import static com.ichi2.libanki.sched.Counts.Queue.*;
import static com.ichi2.libanki.sched.Counts.Queue;
import static com.ichi2.libanki.stats.Stats.SECONDS_PER_DAY;

@SuppressWarnings({"PMD.ExcessiveClassLength", "PMD.AvoidThrowingRawExceptionTypes","PMD.AvoidReassigningParameters",
                    "PMD.NPathComplexity","PMD.MethodNamingConventions","PMD.AvoidBranchingStatementAsLastInLoop",
                    "PMD.SwitchStmtsShouldHaveDefault","PMD.CollapsibleIfStatements","PMD.EmptyIfStmt"})
public class SchedV2 extends AbstractSched {


    // Not in libanki
    private static final int[] FACTOR_ADDITION_VALUES = { -150, 0, 150 };
    public static final int RESCHEDULE_FACTOR = Consts.STARTING_FACTOR;

    protected final int mQueueLimit;
    protected int mReportLimit;
    private final int mDynReportLimit;
    protected int mReps;
    protected boolean mHaveQueues;
    protected boolean mHaveCounts;
    protected @Nullable Integer mToday;
    public long mDayCutoff;
    private long mLrnCutoff;

    protected int mNewCount;
    protected int mLrnCount;
    protected int mRevCount;

    private int mNewCardModulus;

    // Queues
    protected final @NonNull SimpleCardQueue mNewQueue = new SimpleCardQueue(this);



    protected final @NonNull LrnCardQueue mLrnQueue = new LrnCardQueue(this);
    protected final @NonNull SimpleCardQueue mLrnDayQueue = new SimpleCardQueue(this);
    protected final @NonNull SimpleCardQueue mRevQueue = new SimpleCardQueue(this);

    private @NonNull LinkedList<Long> mNewDids = new LinkedList<>();
    protected @NonNull LinkedList<Long> mLrnDids = new LinkedList<>();

    // Not in libanki
    protected @Nullable WeakReference<Activity> mContextReference;

    interface LimitMethod {
        int operation(Deck g);
    }

    /** Given a deck, compute the number of cards to see today, taking its pre-computed limit into consideration.  It
     * considers either review or new cards. Used by WalkingCount to consider all subdecks and parents of a specific
     * decks. */
    
    interface CountMethod {
        int operation(long did, int lim);
    }

    /**
     * The card currently being reviewed.
     *
     * Must not be returned during prefetching (as it is currently shown)
     */
    protected Card mCurrentCard;
    /** The list of parent decks of the current card.
     * Cached for performance .

        Null iff mNextCard is null.*/
    @Nullable
    protected List<Long> mCurrentCardParentsDid;
    /* The next card that will be sent to the reviewer. I.e. the result of a second call to getCard, which is not the
     * current card nor a sibling.
     */

    /**
     * card types: 0=new, 1=lrn, 2=rev, 3=relrn
     * queue types: 0=new, 1=(re)lrn, 2=rev, 3=day (re)lrn,
     *   4=preview, -1=suspended, -2=sibling buried, -3=manually buried
     * revlog types: 0=lrn, 1=rev, 2=relrn, 3=early review
     * positive revlog intervals are in days (rev), negative in seconds (lrn)
     * odue/odid store original due/did when cards moved to filtered deck
     *
     */
    public SchedV2(@NonNull Collection col) {
        super(col);
        mQueueLimit = 50;
        mReportLimit = 99999;
        mDynReportLimit = 99999;
        mReps = 0;
        mToday = null;
        mHaveQueues = false;
        mHaveCounts = false;
        mLrnCutoff = 0;
        _updateCutoff();
    }


    /**
     * Pop the next card from the queue. null if finished.
     */
    public @Nullable Card getCard() {
        _checkDay();
        if (!mHaveQueues) {
            resetQueues(false);
        }
        @Nullable Card card = _getCard();
        if (card == null && !mHaveCounts) {
            // maybe we didn't refill queues because counts were not
            // set. This could only occur if the only card is a buried
            // sibling. So let's try to set counts and check again.
            reset();
            card = _getCard();
        }
        if (card != null) {
            getCol().log(card);
            incrReps();
            // In upstream, counts are decremented when the card is
            // gotten; i.e. in _getLrnCard, _getRevCard and
            // _getNewCard. This can not be done anymore since we use
            // those methods to pre-fetch the next card. Instead we
            // decrement the counts here, when the card is returned to
            // the reviewer.
            decrementCounts(card);
            setCurrentCard(card);
            card.startTimer();
        } else {
            discardCurrentCard();
        }
        if (!mHaveCounts) {
            // Need to reset queues once counts are reset
            TaskManager.launchCollectionTask(new CollectionTask.Reset());
        }
        return card;
    }

    /** Ensures that reset is executed before the next card is selected */
    public void deferReset(@Nullable Card card){
        mHaveQueues = false;
        mHaveCounts = false;
        if (card != null) {
            setCurrentCard(card);
        } else {
            discardCurrentCard();
            getCol().getDecks().update_active();
        }
    }

    public void reset() {
        getCol().getDecks().update_active();
        _updateCutoff();
        resetCounts(false);
        resetQueues(false);
    }
    
    public void resetCounts(@Nullable CancelListener cancelListener) {
        resetCounts(cancelListener, true);
    }

    public void resetCounts(boolean checkCutoff) {
        resetCounts(null, checkCutoff);
    }

    public void resetCounts() {
        resetCounts(null, true);
    }

    /** @param checkCutoff whether we should check cutoff before resetting*/
    private void resetCounts(@Nullable CancelListener cancelListener, boolean checkCutoff) {
        if (checkCutoff) {
            _updateCutoff();
        }

        // Indicate that the counts can't be assumed to be correct since some are computed again and some not
        // In theory it is useless, as anything that change counts should have set mHaveCounts to false
        mHaveCounts = false;
        _resetLrnCount(cancelListener);
        if (isCancelled(cancelListener)) {
            Timber.v("Cancel computing counts of deck %s", getCol().getDecks().current().getString("name"));
            return;
        }
        _resetRevCount(cancelListener);
        if (isCancelled(cancelListener)) {
            Timber.v("Cancel computing counts of deck %s", getCol().getDecks().current().getString("name"));
            return;
        }
        _resetNewCount(cancelListener);
        if (isCancelled(cancelListener)) {
            Timber.v("Cancel computing counts of deck %s", getCol().getDecks().current().getString("name"));
            return;
        }
        mHaveCounts = true;
    }

    /** @param checkCutoff whether we should check cutoff before resetting*/
    private void resetQueues(boolean checkCutoff) {
        if (checkCutoff) {
            _updateCutoff();
        }
        _resetLrnQueue();
        _resetRevQueue();
        _resetNewQueue();
        mHaveQueues = true;
    }


    /**
     * Does all actions required to answer the card. That is:
     * Change its interval, due value, queue, mod time, usn, number of step left (if in learning)
     * Put it in learning if required
     * Log the review.
     * Remove from filtered if required.
     * Remove the siblings for the queue for same day spacing
     * Bury siblings if required by the options
     * Overridden
     *  */
    public void answerCard(@NonNull Card card, @Consts.BUTTON_TYPE int ease) {
        getCol().log();
        discardCurrentCard();
        getCol().markReview(card);
        _burySiblings(card);

        _answerCard(card, ease);

        _updateStats(card, "time", card.timeTaken());
        card.setMod(getTime().intTime());
        card.setUsn(getCol().usn());
        card.flushSched();
    }


    public void _answerCard(@NonNull Card card, @Consts.BUTTON_TYPE int ease) {
        if (_previewingCard(card)) {
            _answerCardPreview(card, ease);
            return;
        }

        card.incrReps();

        if (card.getQueue() == Consts.QUEUE_TYPE_NEW) {
            // came from the new queue, move to learning
            card.setQueue(Consts.QUEUE_TYPE_LRN);
            card.setType(Consts.CARD_TYPE_LRN);
            // init reps to graduation
            card.setLeft(_startingLeft(card));
            // update daily limit
            _updateStats(card, "new");
        }
        if (card.getQueue() == Consts.QUEUE_TYPE_LRN || card.getQueue() == Consts.QUEUE_TYPE_DAY_LEARN_RELEARN) {
            _answerLrnCard(card, ease);
        } else if (card.getQueue() == Consts.QUEUE_TYPE_REV) {
            _answerRevCard(card, ease);
            // Update daily limit
            _updateStats(card, "rev");
        } else {
            throw new RuntimeException("Invalid queue");
        }

        // once a card has been answered once, the original due date
        // no longer applies
        if (card.getODue() > 0) {
            card.setODue(0);
        }
    }

    // note: when adding revlog entries in the future, make sure undo
    // code deletes the entries
    public void _answerCardPreview(@NonNull Card card, @Consts.BUTTON_TYPE int ease) {
        if (ease == Consts.BUTTON_ONE) {
            // Repeat after delay
            card.setQueue(Consts.QUEUE_TYPE_PREVIEW);
            card.setDue(getTime().intTime() + _previewDelay(card));
            mLrnCount += 1;
        } else if (ease == Consts.BUTTON_TWO) {
            // Restore original card state and remove from filtered deck
            _restorePreviewCard(card);
            _removeFromFiltered(card);
        } else {
            // This is in place of the assert
            throw new RuntimeException("Invalid ease");
        }
    }


    /** new count, lrn count, rev count.  */
    public @NonNull Counts counts(@Nullable CancelListener cancelListener) {
        if (!mHaveCounts) {
            resetCounts(cancelListener);
        }
        return new Counts (mNewCount, mLrnCount, mRevCount);
    }


    /**
     * Same as counts(), but also count `card`. In practice, we use it because `card` is in the reviewer and that is the
     * number we actually want.
     * Overridden: left / 1000 in V1
     */
    public @NonNull Counts counts(@NonNull Card card) {
        Counts counts = counts();
        Queue idx = countIdx(card);
        counts.changeCount(idx, 1);
        return counts;
    }

    /**
     * Which of the three numbers shown in reviewer/overview should the card be counted. 0:new, 1:rev, 2: any kind of learning.
     * Overridden: V1 does not have preview
     */
    public Queue countIdx(@NonNull Card card) {
        switch (card.getQueue()) {
            case Consts.QUEUE_TYPE_DAY_LEARN_RELEARN:
            case Consts.QUEUE_TYPE_LRN:
            case Consts.QUEUE_TYPE_PREVIEW:
                return LRN;
            case Consts.QUEUE_TYPE_NEW:
                return NEW;
            case Consts.QUEUE_TYPE_REV:
                return REV;
            default:
                throw new RuntimeException("Index " + card.getQueue() + " does not exists.");
        }
    }


    /** Number of buttons to show in the reviewer for `card`.
     * Overridden */
    public int answerButtons(@NonNull Card card) {
        DeckConfig conf = _cardConf(card);
        if (card.isInDynamicDeck() && !conf.getBoolean("resched")) {
            return 2;
        }
        return 4;
    }


    /**
     * Rev/lrn/time daily stats *************************************************
     * **********************************************
     */

    protected void _updateStats(@NonNull Card card, @NonNull String type) {
        _updateStats(card, type, 1);
    }


    public void _updateStats(@NonNull Card card, @NonNull String type, long cnt) {
        String key = type + "Today";
        long did = card.getDid();
        List<Deck> list = getCol().getDecks().parents(did);
        list.add(getCol().getDecks().get(did));
        for (Deck g : list) {
            JSONArray a = g.getJSONArray(key);
            // add
            a.put(1, a.getLong(1) + cnt);
            getCol().getDecks().save(g);
        }
    }


    public void extendLimits(int newc, int rev) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            super.extendLimits(newc, rev);
            return;
        }

        Deck cur = getCol().getDecks().current();
        List<Deck> decks = getCol().getDecks().parents(cur.getLong("id"));
        decks.add(cur);
        for (long did : getCol().getDecks().children(cur.getLong("id")).values()) {
            decks.add(getCol().getDecks().get(did));
        }
        for (Deck g : decks) {
            // add
            JSONArray today = g.getJSONArray("newToday");
            today.put(1, today.getInt(1) - newc);
            today = g.getJSONArray("revToday");
            today.put(1, today.getInt(1) - rev);
            getCol().getDecks().save(g);
        }
    }


    protected int _walkingCount(@NonNull LimitMethod limFn, @NonNull CountMethod cntFn) {
        return _walkingCount(limFn, cntFn, null);
    }


    /**
     * @param limFn Method sending a deck to the maximal number of card it can have. Normally into account both limits and cards seen today
     * @param cntFn Method sending a deck to the number of card it has got to see today.
     * @param cancelListener Whether the task is not useful anymore
     * @return -1 if it's cancelled. Sum of the results of cntFn, limited by limFn,
     */
    protected int _walkingCount(@NonNull LimitMethod limFn, @NonNull CountMethod cntFn, @Nullable CancelListener cancelListener) {
        int tot = 0;
        HashMap<Long, Integer> pcounts = HashUtil.HashMapInit(getCol().getDecks().count());
        // for each of the active decks
        for (long did : getCol().getDecks().active()) {
            if (isCancelled(cancelListener)) return -1;
            // get the individual deck's limit
            int lim = limFn.operation(getCol().getDecks().get(did));
            if (lim == 0) {
                continue;
            }
            // check the parents
            List<Deck> parents = getCol().getDecks().parents(did);
            for (Deck p : parents) {
                // add if missing
                long id = p.getLong("id");
                if (!pcounts.containsKey(id)) {
                    pcounts.put(id, limFn.operation(p));
                }
                // take minimum of child and parent
                lim = Math.min(pcounts.get(id), lim);
            }
            // see how many cards we actually have
            int cnt = cntFn.operation(did, lim);
            // if non-zero, decrement from parents counts
            for (Deck p : parents) {
                long id = p.getLong("id");
                pcounts.put(id, pcounts.get(id) - cnt);
            }
            // we may also be a parent
            pcounts.put(did, lim - cnt);
            // and add to running total
            tot += cnt;
        }
        return tot;
    }


    /*
      Deck list **************************************************************** *******************************
     */


    /**
     * Returns [deckname, did, rev, lrn, new]
     *
     * Return nulls when deck task is cancelled.
     */
    private @NonNull List<DeckDueTreeNode> deckDueList() {
        return deckDueList(null);
    }

    // Overridden
    /**
     * Return sorted list of all decks.*/
    protected @Nullable List<DeckDueTreeNode> deckDueList(@Nullable CancelListener collectionTask) {
        _checkDay();
        getCol().getDecks().checkIntegrity();
        List<Deck> allDecksSorted = getCol().getDecks().allSorted();
        HashMap<String, Integer[]> lims = HashUtil.HashMapInit(allDecksSorted.size());
        ArrayList<DeckDueTreeNode> deckNodes = new ArrayList<>(allDecksSorted.size());
        Decks.Node childMap = getCol().getDecks().childMap();
        for (Deck deck : allDecksSorted) {
            if (isCancelled(collectionTask)) {
                return null;
            }
            String deckName = deck.getString("name");
            String p = Decks.parent(deckName);
            // new
            int nlim = _deckNewLimitSingle(deck, false);
            Integer plim = null;
            if (!TextUtils.isEmpty(p)) {
                Integer[] parentLims = lims.get(Decks.normalizeName(p));
                // 'temporary for diagnosis of bug #6383'
                Assert.that(parentLims != null, "Deck %s is supposed to have parent %s. It has not be found.", deckName, p);
                nlim = Math.min(nlim, parentLims[0]);
                // reviews
                plim = parentLims[1];
            }
            int _new = _newForDeck(deck.getLong("id"), nlim);
            // learning
            int lrn = _lrnForDeck(deck.getLong("id"));
            // reviews
            int rlim = _deckRevLimitSingle(deck, plim, false);
            int rev = _revForDeck(deck.getLong("id"), rlim, childMap);
            // save to list
            deckNodes.add(new DeckDueTreeNode(deck.getString("name"), deck.getLong("id"), rev, lrn, _new, false, false));
            // add deck as a parent
            lims.put(Decks.normalizeName(deck.getString("name")), new Integer[]{nlim, rlim});
        }
        return deckNodes;
    }

    /** Similar to deck due tree, but ignore the number of cards.

     It may takes a lot of time to compute the number of card, it
     requires multiple database access by deck.  Ignoring this number
     lead to the creation of a tree more quickly.*/
    @RustCleanup("consider updating callers to use col.deckTreeLegacy() directly, and removing this")
    @Override
    public @NonNull
    List<? extends TreeNode<? extends AbstractDeckTreeNode>> quickDeckDueTree() {
        if (!BackendFactory.getDefaultLegacySchema()) {
            return super.quickDeckDueTree();
        }

        // Similar to deckDueList
        ArrayList<DeckTreeNode> allDecksSorted = new ArrayList<>();
        for (JSONObject deck : getCol().getDecks().allSorted()) {
            DeckTreeNode g = new DeckTreeNode(deck.getString("name"), deck.getLong("id"));
            allDecksSorted.add(g);
        }
        // End of the similar part.

        return _groupChildren(allDecksSorted, false);
    }

    @Nullable
    @RustCleanup("once defaultLegacySchema is removed, cancelListener can be removed")
    public List<TreeNode<DeckDueTreeNode>> deckDueTree(@Nullable CancelListener cancelListener) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            return super.deckDueTree(null);
        }

        _checkDay();
        List<DeckDueTreeNode> allDecksSorted = deckDueList(cancelListener);
        if (allDecksSorted == null) {
            return null;
        }
        return _groupChildren(allDecksSorted, true);
    }

    /**
     * @return the tree with `allDecksSorted` content.
     * @param allDecksSorted the set of all decks of the collection. Sorted.
     * @param checkDone Whether the set of deck was checked. If false, we can't assume all decks have parents
     * and that there is no duplicate. Instead, we'll ignore problems.*/
    protected @NonNull <T extends AbstractDeckTreeNode> List<TreeNode<T>> _groupChildren(@NonNull List<T> allDecksSorted, boolean checkDone) {
        return _groupChildren(allDecksSorted, 0, checkDone);
    }

    /**
        @return the tree structure of all decks from @descendants, starting
        at specified depth.

        @param sortedDescendants a list of decks of dept at least depth, having all
        the same first depth name elements, sorted in deck order.
        @param depth The depth of the tree we are creating
        @param checkDone whether the set of deck was checked. If
        false, we can't assume all decks have parents and that there
        is no duplicate. Instead, we'll ignore problems.
     */
    protected @NonNull <T extends AbstractDeckTreeNode> List<TreeNode<T>> _groupChildren(@NonNull List<T> sortedDescendants, int depth, boolean checkDone) {
        List<TreeNode<T>> sortedChildren = new ArrayList<>();
        // group and recurse
        ListIterator<T> it = sortedDescendants.listIterator();
        while (it.hasNext()) {
            T child = it.next();
            String head = child.getDeckNameComponent(depth);
            List<T> sortedDescendantsOfChild  = new ArrayList<>();
            /* Compose the "sortedChildren" node list. The sortedChildren is a
             * list of all the nodes that proceed the current one that
             * contain the same at depth `depth`, except for the
             * current one itself.  I.e., they are subdecks that stem
             * from this descendant.  This is our version of python's
             * itertools.groupby. */
            if (!checkDone && child.getDepth() != depth) {
                Deck deck = getCol().getDecks().get(child.getDid());
                Timber.d("Deck %s (%d)'s parent is missing. Ignoring for quick display.", deck.getString("name"), child.getDid());
                continue;
            }
            while (it.hasNext()) {
                T descendantOfChild = it.next();
                if (head.equals(descendantOfChild.getDeckNameComponent(depth))) {
                    // Same head - add to tail of current head.
                    if (!checkDone && descendantOfChild.getDepth() == depth) {
                        Deck deck = getCol().getDecks().get(descendantOfChild.getDid());
                        Timber.d("Deck %s (%d)'s is a duplicate name. Ignoring for quick display.", deck.getString("name"), descendantOfChild.getDid());
                        continue;
                    }
                    sortedDescendantsOfChild.add(descendantOfChild);
                } else {
                    // We've iterated past this head, so step back in order to use this descendant as the
                    // head in the next iteration of the outer loop.
                    it.previous();
                    break;
                }
            }
            // the childrenNode set contains direct child of `child`, but not
            // any descendants of the children of `child`...
            List<TreeNode<T>> childrenNode = _groupChildren(sortedDescendantsOfChild, depth + 1, checkDone);

            // Add the child nodes, and process the addition
            TreeNode<T> toAdd = new TreeNode<>(child);
            toAdd.getChildren().addAll(childrenNode);
            List<T> childValues = childrenNode.stream().map(TreeNode::getValue).collect(Collectors.toList());
            child.processChildren(getCol(), childValues, "std".equals(getName()));

            sortedChildren.add(toAdd);
        }
        return sortedChildren;
    }


    /*
      Getting the next card ****************************************************
      *******************************************
     */

    /**
     * Return the next due card, or null.
     * Overridden: V1 does not allow dayLearnFirst
     */
    protected @Nullable Card _getCard() {
        // learning card due?
        @Nullable Card c = _getLrnCard(false);
        if (c != null) {
            return c;
        }
        // new first, or time for one?
        if (_timeForNewCard()) {
            c = _getNewCard();
            if (c != null) {
                return c;
            }
        }
        // Day learning first and card due?
        boolean dayLearnFirst = getCol().get_config("dayLearnFirst", false);
        if (dayLearnFirst) {
            c = _getLrnDayCard();
            if (c != null) {
                return c;
            }
        }
        // Card due for review?
        c = _getRevCard();
        if (c != null) {
            return c;
        }
        // day learning card due?
        if (!dayLearnFirst) {
            c = _getLrnDayCard();
            if (c != null) {
                return c;
            }
        }
        // New cards left?
        c = _getNewCard();
        if (c != null) {
            return c;
        }
        // collapse or finish
        return _getLrnCard(true);
    }

    /** similar to _getCard but only fill the queues without taking the card.
     * Returns lists that may contain the next cards.
     */
    protected @NonNull CardQueue<? extends Card.Cache>[] _fillNextCard() {
        // learning card due?
        if (_preloadLrnCard(false)) {
            return new CardQueue<?>[]{mLrnQueue};
        }
        // new first, or time for one?
        if (_timeForNewCard()) {
            if (_fillNew()) {
                return new CardQueue<?>[]{mLrnQueue, mNewQueue};
            }
        }
        // Day learning first and card due?
        boolean dayLearnFirst = getCol().get_config("dayLearnFirst", false);
        if (dayLearnFirst) {
            if (_fillLrnDay()) {
                return new CardQueue<?>[]{mLrnQueue, mLrnDayQueue};
            }
        }
        // Card due for review?
        if (_fillRev()) {
            return new CardQueue<?>[]{mLrnQueue, mRevQueue};
        }
        // day learning card due?
        if (!dayLearnFirst) {
            if (_fillLrnDay()) {
                return new CardQueue<?>[]{mLrnQueue, mLrnDayQueue};
            }
        }
        // New cards left?
        if (_fillNew()) {
            return new CardQueue<?>[]{mLrnQueue, mNewQueue};
        }
        // collapse or finish
        if (_preloadLrnCard(true)) {
            return new CardQueue<?>[]{mLrnQueue};
        }
        return new CardQueue<?>[]{};
    }

    /** pre load the potential next card. It may loads many card because, depending on the time taken, the next card may
     * be a card in review or not. */
    public void preloadNextCard() {
        _checkDay();
        if (!mHaveCounts) {
             resetCounts(false);
        }
        if (!mHaveQueues) {
            resetQueues(false);
        }
        for (CardQueue<? extends Card.Cache> caches: _fillNextCard()) {
            caches.loadFirstCard();
        }
    }


    /**
     * New cards **************************************************************** *******************************
     */

    protected void _resetNewCount() {
        _resetNewCount(null);
    }
    protected void _resetNewCount(@Nullable CancelListener cancelListener) {
        mNewCount = _walkingCount(g -> _deckNewLimitSingle(g, true),
                                  this::_cntFnNew, cancelListener);
    }


    // Used as an argument for _walkingCount() in _resetNewCount() above
    @SuppressWarnings("unused")
    protected int _cntFnNew(long did, int lim) {
        return getCol().getDb().queryScalar(
                "SELECT count() FROM (SELECT 1 FROM cards WHERE did = ? AND queue = " + Consts.QUEUE_TYPE_NEW + " AND id != ? LIMIT ?)",
                did, currentCardId(), lim);
    }


    private void _resetNew() {
        _resetNewCount();
        _resetNewQueue();
    }

    private void _resetNewQueue() {
        mNewDids = new LinkedList<>(getCol().getDecks().active());
        mNewQueue.clear();
        _updateNewCardRatio();
    }

    /**
        @return The id of the note currently in the reviewer. 0 if no
        such card.
     */
    protected long currentCardNid() {
        Card currentCard = mCurrentCard;
        /* mCurrentCard may be set to null when the reviewer gets closed. So we copy it to be sure to avoid
           NullPointerException */
        if (mCurrentCard == null) {
            /* This method is used to determine whether two cards are siblings. Since 0 is not a valid nid, all cards
            will have a nid distinct from 0. As it is used in sql statement, it is not possible to just use a function
            areSiblings()*/
            return 0;
        }
        return currentCard.getNid();
    }

    /**
        @return The id of the card currently in the reviewer. 0 if no
        such card.
     */
    protected long currentCardId() {
        if (mCurrentCard == null) {
            /* This method is used to ensure that query don't return current card. Since 0 is not a valid nid, all cards
            will have a nid distinct from 0. As it is used in sql statement, it is not possible to just use a function
            areSiblings()*/
            return 0;
        }
        return mCurrentCard.getId();
    }

    protected boolean _fillNew() {
        return _fillNew(false);
    }

    private boolean _fillNew(boolean allowSibling) {
        if (!mNewQueue.isEmpty()) {
            return true;
        }
        if (mHaveCounts && mNewCount == 0) {
            return false;
        }
        while (!mNewDids.isEmpty()) {
            long did = mNewDids.getFirst();
            int lim = Math.min(mQueueLimit, _deckNewLimit(did, true));
            if (lim != 0) {
                mNewQueue.clear();
                String idName = (allowSibling) ? "id": "nid";
                long id = (allowSibling) ? currentCardId(): currentCardNid();
                    /* Difference with upstream: we take current card into account.
                     *
                     * When current card is answered, the card is not due anymore, so does not belong to the queue.
                     * Furthermore, _burySiblings ensure that the siblings of the current cards are removed from the
                     * queue to ensure same day spacing. We simulate this action by ensuring that those siblings are not
                     * filled, except if we know there are cards and we didn't find any non-sibling card. This way, the
                     * queue is not empty if it should not be empty (important for the conditional belows), but the
                     * front of the queue contains distinct card.
                 */
                    // fill the queue with the current did
                for (long cid : getCol().getDb().queryLongList("SELECT id FROM cards WHERE did = ? AND queue = " + Consts.QUEUE_TYPE_NEW + " AND " + idName + "!= ? ORDER BY due, ord LIMIT ?", did, id, lim)) {
                    mNewQueue.add(cid);
                }
                if (!mNewQueue.isEmpty()) {
                    // Note: libanki reverses mNewQueue and returns the last element in _getNewCard().
                    // AnkiDroid differs by leaving the queue intact and returning the *first* element
                    // in _getNewCard().
                    return true;
                }
            }
            // nothing left in the deck; move to next
            mNewDids.remove();
        }
        if (mHaveCounts && mNewCount != 0) {
            // if we didn't get a card but the count is non-zero,
            // we need to check again for any cards that were
            // removed from the queue but not buried
            _resetNew();
            return _fillNew(true);
        }
        return false;
    }


    protected @Nullable Card _getNewCard() {
        if (_fillNew()) {
            // mNewCount -= 1; see decrementCounts()
            return mNewQueue.removeFirstCard();
        }
        return null;
    }


    private void _updateNewCardRatio() {
        if (getCol().get_config_int("newSpread") == Consts.NEW_CARDS_DISTRIBUTE) {
            if (mNewCount != 0) {
                mNewCardModulus = (mNewCount + mRevCount) / mNewCount;
                // if there are cards to review, ensure modulo >= 2
                if (mRevCount != 0) {
                    mNewCardModulus = Math.max(2, mNewCardModulus);
                }
                return;
            }
        }
        mNewCardModulus = 0;
    }


    /**
     * @return True if it's time to display a new card when distributing.
     */
    protected boolean _timeForNewCard() {
        if (mHaveCounts && mNewCount == 0) {
            return false;
        }
        @Consts.NEW_CARD_ORDER int spread = getCol().get_config_int("newSpread");
        if (spread == Consts.NEW_CARDS_LAST) {
            return false;
        } else if (spread == Consts.NEW_CARDS_FIRST) {
            return true;
        } else if (mNewCardModulus != 0) {
            // if the counter has not yet been reset, this value is
            // random. This will occur only for the first card of review.
            return (mReps != 0 && (mReps % mNewCardModulus == 0));
        } else {
            return false;
        }
    }


    /**
     *
     * @param considerCurrentCard Whether current card should be counted if it is in this deck
     */
    protected int _deckNewLimit(long did, boolean considerCurrentCard) {
        return _deckNewLimit(did, null, considerCurrentCard);
    }



    /**
     *
     * @param considerCurrentCard Whether current card should be counted if it is in this deck
     */
    protected int _deckNewLimit(long did, LimitMethod fn, boolean considerCurrentCard) {
        if (fn == null) {
            fn = (g -> _deckNewLimitSingle(g, considerCurrentCard));
        }
        @NonNull List<Deck> decks = getCol().getDecks().parents(did);
        decks.add(getCol().getDecks().get(did));
        int lim = -1;
        // for the deck and each of its parents
        int rem = 0;
        for (Deck g : decks) {
            rem = fn.operation(g);
            if (lim == -1) {
                lim = rem;
            } else {
                lim = Math.min(rem, lim);
            }
        }
        return lim;
    }


    /** New count for a single deck. */
    public int _newForDeck(long did, int lim) {
        if (lim == 0) {
            return 0;
        }
        lim = Math.min(lim, mReportLimit);
        return getCol().getDb().queryScalar("SELECT count() FROM (SELECT 1 FROM cards WHERE did = ? AND queue = " + Consts.QUEUE_TYPE_NEW + " LIMIT ?)",
                                        did, lim);
    }


    /**
     * Maximal number of new card still to see today in deck g. It's computed as:
     * the number of new card to see by day according to the deck options
     * minus the number of new cards seen today in deck d or a descendant
     * plus the number of extra new cards to see today in deck d, a parent or a descendant.
     *
     * Limits of its ancestors are not applied.
     * @param considerCurrentCard whether the current card should be taken from the limit (if it belongs to this deck)
     * */
    public int _deckNewLimitSingle(@NonNull Deck g, boolean considerCurrentCard) {
        if (g.isDyn()) {
            return mDynReportLimit;
        }
        long did = g.getLong("id");
        @NonNull DeckConfig c = getCol().getDecks().confForDid(did);
        int lim = Math.max(0, c.getJSONObject("new").getInt("perDay") - g.getJSONArray("newToday").getInt(1));
        // The counts shown in the reviewer does not consider the current card. E.g. if it indicates 6 new card, it means, 6 new card including current card will be seen today.
        // So currentCard does not have to be taken into consideration in this method
        if (considerCurrentCard && currentCardIsInQueueWithDeck(Consts.QUEUE_TYPE_NEW, did)) {
            lim--;
        }
        return lim;
    }

    /**
     * Learning queues *********************************************************** ************************************
     */

    private boolean _updateLrnCutoff(boolean force) {
        long nextCutoff = getTime().intTime() + getCol().get_config_int("collapseTime");
        if (nextCutoff - mLrnCutoff > 60 || force) {
            mLrnCutoff = nextCutoff;
            return true;
        }
        return false;
    }


    private void _maybeResetLrn(boolean force) {
        if (_updateLrnCutoff(force)) {
            _resetLrn();
        }
    }


    // Overridden: V1 has less queues
    protected void _resetLrnCount() {
        _resetLrnCount(null);
    }

    protected void _resetLrnCount(@Nullable CancelListener cancelListener) {
        _updateLrnCutoff(true);
        // sub-day
        mLrnCount = getCol().getDb().queryScalar(
                "SELECT count() FROM cards WHERE did IN " + _deckLimit()
                + " AND queue = " + Consts.QUEUE_TYPE_LRN + " AND id != ? AND due < ?", currentCardId(), mLrnCutoff);
        if (isCancelled(cancelListener)) return;
        // day
        mLrnCount += getCol().getDb().queryScalar(
                "SELECT count() FROM cards WHERE did IN " + _deckLimit() + " AND queue = " + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + " AND due <= ? AND id != ?",
                mToday, currentCardId());
        if (isCancelled(cancelListener)) return;
        // previews
        mLrnCount += getCol().getDb().queryScalar(
                "SELECT count() FROM cards WHERE did IN " + _deckLimit() + " AND queue = " + Consts.QUEUE_TYPE_PREVIEW + " AND id != ? ", currentCardId());
    }


    // Overridden: _updateLrnCutoff not called in V1
    protected void _resetLrn() {
        _resetLrnCount();
        _resetLrnQueue();
    }

    protected void _resetLrnQueue() {
        mLrnQueue.clear();
        mLrnDayQueue.clear();
        mLrnDids = getCol().getDecks().active();
    }


    // sub-day learning
    // Overridden: a single kind of queue in V1
    protected boolean _fillLrn() {
        if (mHaveCounts && mLrnCount == 0) {
            return false;
        }
        if (!mLrnQueue.isEmpty()) {
            return true;
        }
        long cutoff = getTime().intTime() + getCol().get_config_long("collapseTime");
        mLrnQueue.clear();
        /* Difference with upstream: Current card can't come in the queue.
             *
             * In standard usage, a card is not requested before the previous card is marked as reviewed. However, if we
             * decide to query a second card sooner, we don't want to get the same card a second time. This simulate
             * _getLrnCard which did remove the card from the queue. _sortIntoLrn will add the card back to the queue if
             * required when the card is reviewed.
             */
        try (Cursor cur = getCol()
                    .getDb()
                    .query(
                            "SELECT due, id FROM cards WHERE did IN " + _deckLimit() + " AND queue IN (" + Consts.QUEUE_TYPE_LRN + ", " + Consts.QUEUE_TYPE_PREVIEW + ") AND due < ?"
                            + " AND id != ? LIMIT ?", cutoff, currentCardId(), mReportLimit)) {
            mLrnQueue.setFilled();
            while (cur.moveToNext()) {
                mLrnQueue.add(cur.getLong(0), cur.getLong(1));
            }
            // as it arrives sorted by did first, we need to sort it
            mLrnQueue.sort();
            return !mLrnQueue.isEmpty();
        }
    }


    // Overridden: no _maybeResetLrn in V1
    protected @Nullable Card _getLrnCard(boolean collapse) {
        _maybeResetLrn(collapse && mLrnCount == 0);
        if (_fillLrn()) {
            long cutoff = getTime().intTime();
            if (collapse) {
                cutoff += getCol().get_config_int("collapseTime");
            }
            if (mLrnQueue.getFirstDue() < cutoff) {
                return mLrnQueue.removeFirstCard();
                // mLrnCount -= 1; see decrementCounts()
            }
        }
        return null;
    }


    protected boolean _preloadLrnCard(boolean collapse) {
        _maybeResetLrn(collapse && mLrnCount == 0);
        if (_fillLrn()) {
            long cutoff = getTime().intTime();
            if (collapse) {
                cutoff += getCol().get_config_int("collapseTime");
            }
            // mLrnCount -= 1; see decrementCounts()
            return mLrnQueue.getFirstDue() < cutoff;
        }
        return false;
    }


    // daily learning
    protected boolean _fillLrnDay() {
        if (mHaveCounts && mLrnCount == 0) {
            return false;
        }
        if (!mLrnDayQueue.isEmpty()) {
            return true;
        }
        while (!mLrnDids.isEmpty()) {
            long did = mLrnDids.getFirst();
            // fill the queue with the current did
            mLrnDayQueue.clear();
                /* Difference with upstream:
                 * Current card can't come in the queue.
                 *
                 * In standard usage, a card is not requested before
                 * the previous card is marked as reviewed. However,
                 * if we decide to query a second card sooner, we
                 * don't want to get the same card a second time. This
                 * simulate _getLrnDayCard which did remove the card
                 * from the queue.
                 */
            for (long cid : getCol().getDb().queryLongList(
                                "SELECT id FROM cards WHERE did = ? AND queue = " + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + " AND due <= ? and id != ? LIMIT ?",
                                did, mToday, currentCardId(), mQueueLimit)) {
                mLrnDayQueue.add(cid);
            }
            if (!mLrnDayQueue.isEmpty()) {
                // order
                Random r = new Random();
                r.setSeed(mToday);
                mLrnDayQueue.shuffle(r);
                // is the current did empty?
                if (mLrnDayQueue.size() < mQueueLimit) {
                    mLrnDids.remove();
                }
                return true;
            }
            // nothing left in the deck; move to next
            mLrnDids.remove();
        }
        return false;
    }


    protected @Nullable Card _getLrnDayCard() {
        if (_fillLrnDay()) {
            // mLrnCount -= 1; see decrementCounts()
            return mLrnDayQueue.removeFirstCard();
        }
        return null;
    }


    // Overridden
    protected void _answerLrnCard(@NonNull Card card, @Consts.BUTTON_TYPE int ease) {
        JSONObject conf = _lrnConf(card);
        @Consts.REVLOG_TYPE int type;
        if (card.getType() == Consts.CARD_TYPE_REV || card.getType() == Consts.CARD_TYPE_RELEARNING) {
            type = Consts.REVLOG_RELRN;
        } else {
            type = Consts.REVLOG_LRN;
        }

        // lrnCount was decremented once when card was fetched
        int lastLeft = card.getLeft();
        boolean leaving = false;

        // immediate graduate?
        if (ease == Consts.BUTTON_FOUR) {
            _rescheduleAsRev(card, conf, true);
            leaving = true;
        // next step?
        } else if (ease == Consts.BUTTON_THREE) {
            // graduation time?
            if ((card.getLeft() % 1000) - 1 <= 0) {
                _rescheduleAsRev(card, conf, false);
                leaving = true;
            } else {
                _moveToNextStep(card, conf);
            }
        } else if (ease == Consts.BUTTON_TWO) {
            _repeatStep(card, conf);
        } else {
            // move back to first step
            _moveToFirstStep(card, conf);
        }
        _logLrn(card, ease, conf, leaving, type, lastLeft);
    }


    protected void _updateRevIvlOnFail(@NonNull Card card, @NonNull JSONObject conf) {
        card.setLastIvl(card.getIvl());
        card.setIvl(_lapseIvl(card, conf));
    }


    private int _moveToFirstStep(@NonNull Card card, @NonNull JSONObject conf) {
        card.setLeft(_startingLeft(card));

        // relearning card?
        if (card.getType() == Consts.CARD_TYPE_RELEARNING) {
            _updateRevIvlOnFail(card, conf);
        }

        return _rescheduleLrnCard(card, conf);
    }


    private void _moveToNextStep(@NonNull Card card, @NonNull JSONObject conf) {
        // decrement real left count and recalculate left today
        int left = (card.getLeft() % 1000) - 1;
        card.setLeft(_leftToday(conf.getJSONArray("delays"), left) * 1000 + left);

        _rescheduleLrnCard(card, conf);
    }


    private void _repeatStep(@NonNull Card card, @NonNull JSONObject conf) {
        int delay = _delayForRepeatingGrade(conf, card.getLeft());
        _rescheduleLrnCard(card, conf, delay);
    }


    private int _rescheduleLrnCard(@NonNull Card card, @NonNull JSONObject conf) {
        return _rescheduleLrnCard(card, conf, null);
    }


    private int _rescheduleLrnCard(@NonNull Card card, @NonNull JSONObject conf, @Nullable Integer delay) {
        // normal delay for the current step?
        if (delay == null) {
            delay = _delayForGrade(conf, card.getLeft());
        }
        card.setDue(getTime().intTime() + delay);

        // due today?
        if (card.getDue() < mDayCutoff) {
            // Add some randomness, up to 5 minutes or 25%
            int maxExtra = Math.min(300, (int)(delay * 0.25));
            int fuzz = new Random().nextInt(Math.max(maxExtra, 1));
            card.setDue(Math.min(mDayCutoff - 1, card.getDue() + fuzz));
            card.setQueue(Consts.QUEUE_TYPE_LRN);
            if (card.getDue() < (getTime().intTime() + getCol().get_config_int("collapseTime"))) {
                mLrnCount += 1;
                // if the queue is not empty and there's nothing else to do, make
                // sure we don't put it at the head of the queue and end up showing
                // it twice in a row
                if (!mLrnQueue.isEmpty() && revCount() == 0 && newCount() == 0) {
                    long smallestDue = mLrnQueue.getFirstDue();
                    card.setDue(Math.max(card.getDue(), smallestDue + 1));
                }
                _sortIntoLrn(card.getDue(), card.getId());
            }
        } else {
            // the card is due in one or more days, so we need to use the day learn queue
            long ahead = ((card.getDue() - mDayCutoff) / SECONDS_PER_DAY) + 1;
            card.setDue(mToday + ahead);
            card.setQueue(Consts.QUEUE_TYPE_DAY_LEARN_RELEARN);
        }
        return delay;
    }


    protected int _delayForGrade(JSONObject conf, int left) {
        left = left % 1000;
        try {
            double delay;
            JSONArray delays = conf.getJSONArray("delays");
            int len = delays.length();
            try {
                delay = delays.getDouble(len - left);
            } catch (JSONException e) {
                Timber.w(e);
                if (conf.getJSONArray("delays").length() > 0) {
                    delay = conf.getJSONArray("delays").getDouble(0);
                } else {
                    // user deleted final step; use dummy value
                    delay = 1.0;
                }
            }
            return (int) (delay * 60.0);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }


    private int _delayForRepeatingGrade(@NonNull JSONObject conf, int left) {
        // halfway between last and  next
        int delay1 = _delayForGrade(conf, left);
        int delay2;
        if (conf.getJSONArray("delays").length() > 1) {
            delay2 = _delayForGrade(conf, left - 1);
        } else {
            delay2 = delay1 * 2;
        }
        return (delay1 + Math.max(delay1, delay2)) / 2;
    }


    // Overridden: RELEARNING does not exists in V1
    protected @NonNull JSONObject _lrnConf(@NonNull Card card) {
        if (card.getType() == Consts.CARD_TYPE_REV || card.getType() == Consts.CARD_TYPE_RELEARNING) {
            return _lapseConf(card);
        } else {
            return _newConf(card);
        }
    }


    // Overridden
    protected void _rescheduleAsRev(@NonNull Card card, @NonNull JSONObject conf, boolean early) {
        boolean lapse = (card.getType() == Consts.CARD_TYPE_REV || card.getType() == Consts.CARD_TYPE_RELEARNING);
        if (lapse) {
            _rescheduleGraduatingLapse(card, early);
        } else {
            _rescheduleNew(card, conf, early);
        }
        // if we were dynamic, graduating means moving back to the old deck
        if (card.isInDynamicDeck()) {
            _removeFromFiltered(card);
        }
    }

    private void _rescheduleGraduatingLapse(@NonNull Card card, boolean early) {
        if (early) {
            card.setIvl(card.getIvl() + 1);
        }
        card.setDue(mToday + card.getIvl());
        card.setQueue(Consts.QUEUE_TYPE_REV);
        card.setType(Consts.CARD_TYPE_REV);
    }


    // Overridden: V1 has type rev for relearning
    protected int _startingLeft(@NonNull Card card) {
        JSONObject conf;
        if (card.getType() == Consts.CARD_TYPE_RELEARNING) {
            conf = _lapseConf(card);
        } else {
            conf = _lrnConf(card);
        }
        int tot = conf.getJSONArray("delays").length();
        int tod = _leftToday(conf.getJSONArray("delays"), tot);
        return tot + tod * 1000;
    }


    /** the number of steps that can be completed by the day cutoff */
    protected int _leftToday(@NonNull JSONArray delays, int left) {
        return _leftToday(delays, left, 0);
    }


    private int _leftToday(@NonNull JSONArray delays, int left, long now) {
        if (now == 0) {
            now = getTime().intTime();
        }
        int ok = 0;
        int offset = Math.min(left, delays.length());
        for (int i = 0; i < offset; i++) {
            now += (int) (delays.getDouble(delays.length() - offset + i) * 60.0);
            if (now > mDayCutoff) {
                break;
            }
            ok = i;
        }
        return ok + 1;
    }


    protected int _graduatingIvl(@NonNull Card card, @NonNull JSONObject conf, boolean early) {
        return _graduatingIvl(card, conf, early, true);
    }


    private int _graduatingIvl(@NonNull Card card, @NonNull JSONObject conf, boolean early, boolean fuzz) {
        if (card.getType() == Consts.CARD_TYPE_REV || card.getType() == Consts.CARD_TYPE_RELEARNING) {
            int bonus = early ? 1 : 0;
            return card.getIvl() + bonus;
        }
        int ideal;
        JSONArray ints = conf.getJSONArray("ints");
        if (!early) {
            // graduate
            ideal = ints.getInt(0);
        } else {
            // early remove
            ideal = ints.getInt(1);
        }
        if (fuzz) {
            ideal = _fuzzedIvl(ideal);
        }
        return ideal;
    }


    /** Reschedule a new card that's graduated for the first time.
     * Overridden: V1 does not set type and queue*/
    private void _rescheduleNew(@NonNull Card card, @NonNull JSONObject conf, boolean early) {
        card.setIvl(_graduatingIvl(card, conf, early));
        card.setDue(mToday + card.getIvl());
        card.setFactor(conf.getInt("initialFactor"));
        card.setType(Consts.CARD_TYPE_REV);
        card.setQueue(Consts.QUEUE_TYPE_REV);
    }


    protected void _logLrn(@NonNull Card card, @Consts.BUTTON_TYPE int ease, @NonNull JSONObject conf, boolean leaving, @Consts.REVLOG_TYPE int type, int lastLeft) {
        int lastIvl = -(_delayForGrade(conf, lastLeft));
        int ivl = leaving ? card.getIvl() : -(_delayForGrade(conf, card.getLeft()));
        log(card.getId(), getCol().usn(), ease, ivl, lastIvl, card.getFactor(), card.timeTaken(), type);
    }

    protected void log(long id, int usn, @Consts.BUTTON_TYPE int ease, int ivl, int lastIvl, int factor, int timeTaken, @Consts.REVLOG_TYPE int type) {
        try {
            getCol().getDb().execute("INSERT INTO revlog VALUES (?,?,?,?,?,?,?,?,?)",
                    getTime().intTimeMS(), id, usn, ease, ivl, lastIvl, factor, timeTaken, type);
        } catch (SQLiteConstraintException e) {
            Timber.w(e);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e1) {
                throw new RuntimeException(e1);
            }
            log(id, usn, ease, ivl, lastIvl, factor, timeTaken, type);
        }
    }


    // Overridden: uses left/1000 in V1
    private int _lrnForDeck(long did) {
        try {
            int cnt = getCol().getDb().queryScalar(
                    "SELECT count() FROM (SELECT null FROM cards WHERE did = ?"
                            + " AND queue = " + Consts.QUEUE_TYPE_LRN + " AND due < ?"
                            + " LIMIT ?)",
                    did, (getTime().intTime() + getCol().get_config_int("collapseTime")), mReportLimit);
            return cnt + getCol().getDb().queryScalar(
                    "SELECT count() FROM (SELECT null FROM cards WHERE did = ?"
                            + " AND queue = " + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + " AND due <= ?"
                            + " LIMIT ?)",
                    did, mToday, mReportLimit);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


    /*
      Reviews ****************************************************************** *****************************
     */

    /**
     * Maximal number of rev card still to see today in current deck. It's computed as:
     * the number of rev card to see by day according to the deck options
     * minus the number of rev cards seen today in this deck or a descendant
     * plus the number of extra cards to see today in this deck, a parent or a descendant.
     *
     * Respects the limits of its ancestor. Current card is treated the same way as other cards.
     * @param considerCurrentCard whether the current card should be taken from the limit (if it belongs to this deck)
     * */
    private int _currentRevLimit(boolean considerCurrentCard) {
        Deck d = getCol().getDecks().get(getCol().getDecks().selected(), false);
        return _deckRevLimitSingle(d, considerCurrentCard);
    }

    /**
     * Maximal number of rev card still to see today in deck d. It's computed as:
     * the number of rev card to see by day according to the deck options
     * minus the number of rev cards seen today in deck d or a descendant
     * plus the number of extra cards to see today in deck d, a parent or a descendant.
     *
     * Respects the limits of its ancestor
     * Overridden: V1 does not consider parents limit
     * @param considerCurrentCard whether the current card should be taken from the limit (if it belongs to this deck)
     * */
    protected int _deckRevLimitSingle(@Nullable Deck d, boolean considerCurrentCard) {
        return _deckRevLimitSingle(d, null, considerCurrentCard);
    }


    /**
     * Maximal number of rev card still to see today in deck d. It's computed as:
     * the number of rev card to see by day according to the deck options
     * minus the number of rev cards seen today in deck d or a descendant
     * plus the number of extra cards to see today in deck d, a parent or a descendant.
     *
     * Respects the limits of its ancestor, either given as parentLimit, or through direct computation.
     * @param parentLimit Limit of the parent, this is an upper bound on the limit of this deck
     * @param considerCurrentCard whether the current card should be taken from the limit (if it belongs to this deck)
     * */
    private int _deckRevLimitSingle(@Nullable Deck d, Integer parentLimit, boolean considerCurrentCard) {
        // invalid deck selected?
        if (d == null) {
            return 0;
        }
        if (d.isDyn()) {
            return mDynReportLimit;
        }
        long did = d.getLong("id");
        @NonNull DeckConfig c = getCol().getDecks().confForDid(did);
        int lim = Math.max(0, c.getJSONObject("rev").getInt("perDay") - d.getJSONArray("revToday").getInt(1));
        // The counts shown in the reviewer does not consider the current card. E.g. if it indicates 6 rev card, it means, 6 rev card including current card will be seen today.
        // So currentCard does not have to be taken into consideration in this method
        if (considerCurrentCard && currentCardIsInQueueWithDeck(Consts.QUEUE_TYPE_REV, did)) {
            lim--;
        }

        return lim;
    }


    protected int _revForDeck(long did, int lim, @NonNull Decks.Node childMap) {
        List<Long> dids = getCol().getDecks().childDids(did, childMap);
        dids.add(0, did);
        lim = Math.min(lim, mReportLimit);
        return getCol().getDb().queryScalar("SELECT count() FROM (SELECT 1 FROM cards WHERE did in " + Utils.ids2str(dids) + " AND queue = " + Consts.QUEUE_TYPE_REV + " AND due <= ? LIMIT ?)",
                                        mToday, lim);
    }

    // Overridden: V1 uses _walkingCount
    protected void _resetRevCount() {
        _resetRevCount(null);
    }
    protected void _resetRevCount(@Nullable CancelListener cancelListener) {
        int lim = _currentRevLimit(true);
        if (isCancelled(cancelListener)) return;
        mRevCount = getCol().getDb().queryScalar("SELECT count() FROM (SELECT id FROM cards WHERE did in " + _deckLimit() + " AND queue = " + Consts.QUEUE_TYPE_REV + " AND due <= ? AND id != ? LIMIT ?)",
                                             mToday, currentCardId(), lim);
    }


    // Overridden: V1 remove clear
    protected void _resetRev() {
        _resetRevCount();
        _resetRevQueue();
    }

    protected void _resetRevQueue() {
        mRevQueue.clear();
    }


    protected boolean _fillRev() {
        return _fillRev(false);
    }

    // Override: V1 loops over dids
    protected boolean _fillRev(boolean allowSibling) {
        if (!mRevQueue.isEmpty()) {
            return true;
        }
        if (mHaveCounts && mRevCount == 0) {
            return false;
        }
        int lim = Math.min(mQueueLimit, _currentRevLimit(true));
        if (lim != 0) {
            mRevQueue.clear();
            // fill the queue with the current did
            String idName = (allowSibling) ? "id": "nid";
            long id = (allowSibling) ? currentCardId(): currentCardNid();
                /* Difference with upstream: we take current card into account.
                 *
                 * When current card is answered, the card is not due anymore, so does not belong to the queue.
                 * Furthermore, _burySiblings ensure that the siblings of the current cards are removed from the queue
                 * to ensure same day spacing. We simulate this action by ensuring that those siblings are not filled,
                 * except if we know there are cards and we didn't find any non-sibling card. This way, the queue is not
                 * empty if it should not be empty (important for the conditional belows), but the front of the queue
                 * contains distinct card.
                 */
                // fill the queue with the current did
            try (Cursor cur = getCol().getDb().query("SELECT id FROM cards WHERE did in " + _deckLimit() + " AND queue = " + Consts.QUEUE_TYPE_REV + " AND due <= ? AND " + idName + " != ?"
                               + " ORDER BY due, random()  LIMIT ?",
                               mToday, id, lim)) {
                while (cur.moveToNext()) {
                    mRevQueue.add(cur.getLong(0));
                }
            }
            if (!mRevQueue.isEmpty()) {
                // preserve order
                // Note: libanki reverses mRevQueue and returns the last element in _getRevCard().
                // AnkiDroid differs by leaving the queue intact and returning the *first* element
                // in _getRevCard().
                return true;
            }
        }
        if (mHaveCounts && mRevCount != 0) {
            // if we didn't get a card but the count is non-zero,
            // we need to check again for any cards that were
            // removed from the queue but not buried
            _resetRev();
            return _fillRev(true);
        }
        return false;
    }


    protected @Nullable Card _getRevCard() {
        if (_fillRev()) {
            // mRevCount -= 1; see decrementCounts()
            return mRevQueue.removeFirstCard();
        } else {
            return null;
        }
    }


    /**
     * Answering a review card **************************************************
     * *********************************************
     */

    // Overridden: v1 does not deal with early
    protected void _answerRevCard(@NonNull Card card, @Consts.BUTTON_TYPE int ease) {
        int delay = 0;
        boolean early = card.isInDynamicDeck() && (card.getODue() > mToday);
        int type = early ? 3 : 1;
        if (ease == Consts.BUTTON_ONE) {
            delay = _rescheduleLapse(card);
        } else {
            _rescheduleRev(card, ease, early);
        }
        _logRev(card, ease, delay, type);
    }


    // Overridden
    protected int _rescheduleLapse(@NonNull Card card) {
        JSONObject conf = _lapseConf(card);
        card.setLapses(card.getLapses() + 1);
        card.setFactor(Math.max(1300, card.getFactor() - 200));
        int delay;
         boolean suspended = _checkLeech(card, conf) && card.getQueue() == Consts.QUEUE_TYPE_SUSPENDED;
        if (conf.getJSONArray("delays").length() != 0 && !suspended) {
            card.setType(Consts.CARD_TYPE_RELEARNING);
            delay = _moveToFirstStep(card, conf);
        } else {
            // no relearning steps
            _updateRevIvlOnFail(card, conf);
            _rescheduleAsRev(card, conf, false);
            // need to reset the queue after rescheduling
            if (suspended) {
                card.setQueue(Consts.QUEUE_TYPE_SUSPENDED);
            }
            delay = 0;
        }

        return delay;
    }


    private int _lapseIvl(@NonNull Card card, @NonNull JSONObject conf) {
        return Math.max(1, Math.max(conf.getInt("minInt"), (int)(card.getIvl() * conf.getDouble("mult"))));
    }


    protected void _rescheduleRev(@NonNull Card card, @Consts.BUTTON_TYPE int ease, boolean early) {
        // update interval
        card.setLastIvl(card.getIvl());
        if (early) {
            _updateEarlyRevIvl(card, ease);
        } else {
            _updateRevIvl(card, ease);
        }

        // then the rest
        card.setFactor(Math.max(1300, card.getFactor() + FACTOR_ADDITION_VALUES[ease - 2]));
        card.setDue(mToday + card.getIvl());

        // card leaves filtered deck
        _removeFromFiltered(card);
    }


    protected void _logRev(@NonNull Card card, @Consts.BUTTON_TYPE int ease, int delay, int type) {
        log(card.getId(), getCol().usn(), ease, ((delay != 0) ? (-delay) : card.getIvl()), card.getLastIvl(),
                card.getFactor(), card.timeTaken(), type);
    }


    /*
      Interval management ******************************************************
      *****************************************
     */

    /**
     * Next interval for CARD, given EASE.
     */
    protected int _nextRevIvl(@NonNull Card card, @Consts.BUTTON_TYPE int ease, boolean fuzz) {
        long delay = _daysLate(card);
        JSONObject conf = _revConf(card);
        double fct = card.getFactor() / 1000.0;
        double hardFactor = conf.optDouble("hardFactor", 1.2);
        int hardMin;
        if (hardFactor > 1) {
            hardMin = card.getIvl();
        } else {
            hardMin = 0;
        }

        int ivl2 = _constrainedIvl(card.getIvl() * hardFactor, conf, hardMin, fuzz);
        if (ease == Consts.BUTTON_TWO) {
            return ivl2;
        }

        int ivl3 = _constrainedIvl((card.getIvl() + delay / 2) * fct, conf, ivl2, fuzz);
        if (ease == Consts.BUTTON_THREE) {
            return ivl3;
        }

        return _constrainedIvl((
                                    (card.getIvl() + delay) * fct * conf.getDouble("ease4")), conf, ivl3, fuzz);
    }

    public int _fuzzedIvl(int ivl) {
        Pair<Integer, Integer> minMax = _fuzzIvlRange(ivl);
        // Anki's python uses random.randint(a, b) which returns x in [a, b] while the eq Random().nextInt(a, b)
        // returns x in [0, b-a), hence the +1 diff with libanki
        return (new Random().nextInt(minMax.second - minMax.first + 1)) + minMax.first;
    }


    public static @NonNull Pair<Integer, Integer> _fuzzIvlRange(int ivl) {
        int fuzz;
        if (ivl < 2) {
            return new Pair<>(1, 1);
        } else if (ivl == 2) {
            return new Pair<>(2, 3);
        } else if (ivl < 7) {
            fuzz = (int)(ivl * 0.25);
        } else if (ivl < 30) {
            fuzz = Math.max(2, (int)(ivl * 0.15));
        } else {
            fuzz = Math.max(4, (int)(ivl * 0.05));
        }
        // fuzz at least a day
        fuzz = Math.max(fuzz, 1);
        return new Pair<>(ivl - fuzz, ivl + fuzz);
    }


    protected int _constrainedIvl(double ivl, @NonNull JSONObject conf, double prev, boolean fuzz) {
        int newIvl = (int) (ivl * conf.optDouble("ivlFct", 1));
        if (fuzz) {
            newIvl = _fuzzedIvl(newIvl);
        }

        newIvl = (int) Math.max(Math.max(newIvl, prev + 1), 1);
        newIvl = Math.min(newIvl, conf.getInt("maxIvl"));

        return newIvl;
    }


    /**
     * Number of days later than scheduled.
     */
    protected long _daysLate(Card card) {
        long due = card.isInDynamicDeck() ? card.getODue() : card.getDue();
        return Math.max(0, mToday - due);
    }


    // Overridden
    protected void _updateRevIvl(@NonNull Card card, @Consts.BUTTON_TYPE int ease) {
        card.setIvl(_nextRevIvl(card, ease, true));
    }


    private void _updateEarlyRevIvl(@NonNull Card card, @Consts.BUTTON_TYPE int ease) {
        card.setIvl(_earlyReviewIvl(card, ease));
    }


    /** next interval for card when answered early+correctly */
    private int _earlyReviewIvl(@NonNull Card card, @Consts.BUTTON_TYPE int ease) {
        if (!card.isInDynamicDeck() || card.getType() != Consts.CARD_TYPE_REV || card.getFactor() == 0) {
            throw new RuntimeException("Unexpected card parameters");
        }
        if (ease <= 1) {
            throw new RuntimeException("Ease must be greater than 1");
        }

        long elapsed = card.getIvl() - (card.getODue() - mToday);

        @NonNull JSONObject conf = _revConf(card);

        double easyBonus = 1;
        // early 3/4 reviews shouldn't decrease previous interval
        double minNewIvl = 1;

        double factor;
        if (ease == Consts.BUTTON_TWO)  {
            factor = conf.optDouble("hardFactor", 1.2);
            // hard cards shouldn't have their interval decreased by more than 50%
            // of the normal factor
            minNewIvl = factor / 2;
        } else if (ease == 3) {
            factor = card.getFactor() / 1000.0;
        } else { // ease == 4
            factor = card.getFactor() / 1000.0;
            double ease4 = conf.getDouble("ease4");
            // 1.3 -> 1.15
            easyBonus = ease4 - (ease4 - 1)/2;
        }

        double ivl = Math.max(elapsed * factor, 1);

        // cap interval decreases
        ivl = Math.max(card.getIvl() * minNewIvl, ivl) * easyBonus;

        return _constrainedIvl(ivl, conf, 0, false);
    }


    /*
      Dynamic deck handling ******************************************************************
      *****************************
     */

    @Override
    public void rebuildDyn(long did) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            super.rebuildDyn(did);
            return;
        }

        Deck deck = getCol().getDecks().get(did);
        if (deck.isStd()) {
            Timber.e("error: deck is not a filtered deck");
            return;
        }
        // move any existing cards back first, then fill
        emptyDyn(did);
        int cnt = _fillDyn(deck);
        if (cnt == 0) {
            return;
        }
        // and change to our new deck
        getCol().getDecks().select(did);
    }


    /**
     * Whether the filtered deck is empty
     * Overridden
     */
    private int _fillDyn(Deck deck) {
        int start = -100000;
        int total = 0;
        List<Long> ids;
        JSONArray terms = deck.getJSONArray("terms");
        for (JSONArray term: terms.jsonArrayIterable()) {
            String search = term.getString(0);
            int limit = term.getInt(1);
            int order = term.getInt(2);

            String orderLimit = _dynOrder(order, limit);
            if (!TextUtils.isEmpty(search.trim())) {
                search = String.format(Locale.US, "(%s)", search);
            }
            search = String.format(Locale.US, "%s -is:suspended -is:buried -deck:filtered", search);
            ids = getCol().findCards(search, new SortOrder.AfterSqlOrderBy(orderLimit));
            if (ids.isEmpty()) {
                return total;
            }
            // move the cards over
            getCol().log(deck.getLong("id"), ids);
            _moveToDyn(deck.getLong("id"), ids, start + total);
            total += ids.size();
        }
        return total;
    }


    public void emptyDyn(long did) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            super.emptyDyn(did);
            return;
        }

        emptyDyn("did = " + did);
    }

    /**
     * Generates the required SQL for order by and limit clauses, for dynamic decks.
     *
     * @param o deck["order"]
     * @param l deck["limit"]
     * @return The generated SQL to be suffixed to "select ... from ... order by "
     */
    protected @NonNull String _dynOrder(@Consts.DYN_PRIORITY int o, int l) {
        String t;
        switch (o) {
            case Consts.DYN_OLDEST:
                t = "c.mod";
                break;
            case Consts.DYN_RANDOM:
                t = "random()";
                break;
            case Consts.DYN_SMALLINT:
                t = "ivl";
                break;
            case Consts.DYN_BIGINT:
                t = "ivl desc";
                break;
            case Consts.DYN_LAPSES:
                t = "lapses desc";
                break;
            case Consts.DYN_ADDED:
                t = "n.id";
                break;
            case Consts.DYN_REVADDED:
                t = "n.id desc";
                break;
            case Consts.DYN_DUEPRIORITY:
                t = String.format(Locale.US,
                        "(case when queue=" + Consts.QUEUE_TYPE_REV + " and due <= %d then (ivl / cast(%d-due+0.001 as real)) else 100000+due end)",
                        mToday, mToday);
                break;
            case Consts.DYN_DUE:
            default:
                // if we don't understand the term, default to due order
                t = "c.due";
                break;
        }
        return t + " limit " + l;
    }


    protected void _moveToDyn(long did, @NonNull List<Long> ids, int start) {
        Deck deck = getCol().getDecks().get(did);
        ArrayList<Object[]> data = new ArrayList<>(ids.size());
        int u = getCol().usn();
        int due = start;
        for (Long id : ids) {
            data.add(new Object[] {
                did, due, u, id
            });
            due += 1;
        }
        String queue = "";
        if (!deck.getBoolean("resched")) {
            queue = ", queue = " + Consts.QUEUE_TYPE_REV + "";
        }

        getCol().getDb().executeMany(
                "UPDATE cards SET odid = did, " +
                        "odue = due, did = ?, due = (case when due <= 0 then due else ? end), usn = ? " + queue + " WHERE id = ?", data);
    }


    private void _removeFromFiltered(@NonNull Card card) {
        if (card.isInDynamicDeck()) {
            card.setDid(card.getODid());
            card.setODue(0);
            card.setODid(0);
        }
    }


    private void _restorePreviewCard(@NonNull Card card) {
        if (!card.isInDynamicDeck()) {
            throw new RuntimeException("ODid wasn't set");
        }

        card.setDue(card.getODue());

        // learning and relearning cards may be seconds-based or day-based;
        // other types map directly to queues
        if (card.getType() == Consts.CARD_TYPE_LRN || card.getType() == Consts.CARD_TYPE_RELEARNING) {
            if (card.getODue() > 1000000000) {
                card.setQueue(Consts.QUEUE_TYPE_LRN);
            } else {
                card.setQueue(Consts.QUEUE_TYPE_DAY_LEARN_RELEARN);
            }
        } else {
            //noinspection WrongConstant
            card.setQueue(card.getType());
        }
    }


    /*
      Leeches ****************************************************************** *****************************
     */


    /** Leech handler. True if card was a leech.
        Overridden: in V1, due and did are changed*/
    protected boolean _checkLeech(@NonNull Card card, @NonNull JSONObject conf) {
        int lf = conf.getInt("leechFails");
        if (lf == 0) {
            return false;
        }
        // if over threshold or every half threshold reps after that
        if (card.getLapses() >= lf && (card.getLapses() - lf) % Math.max(lf / 2, 1) == 0) {
            // add a leech tag
            Note n = card.note();
            n.addTag("leech");
            n.flush();
            // handle
            if (conf.getInt("leechAction") == Consts.LEECH_SUSPEND) {
                card.setQueue(Consts.QUEUE_TYPE_SUSPENDED);
            }
            // notify UI
            if (mContextReference != null) {
                Activity context = mContextReference.get();
                leech(card, context);
            }
            return true;
        }
        return false;
    }


    /**
     * Tools ******************************************************************** ***************************
     */

    // Overridden: different delays for filtered cards.
    protected @NonNull JSONObject _newConf(@NonNull Card card) {
        DeckConfig conf = _cardConf(card);
        if (!card.isInDynamicDeck()) {
            return conf.getJSONObject("new");
        }
        // dynamic deck; override some attributes, use original deck for others
        DeckConfig oconf = getCol().getDecks().confForDid(card.getODid());
        JSONObject dict = new JSONObject();
        // original deck
        dict.put("ints", oconf.getJSONObject("new").getJSONArray("ints"));
        dict.put("initialFactor", oconf.getJSONObject("new").getInt("initialFactor"));
        dict.put("bury", oconf.getJSONObject("new").optBoolean("bury", true));
        dict.put("delays", oconf.getJSONObject("new").getJSONArray("delays"));
        // overrides
        dict.put("separate", conf.getBoolean("separate"));
        dict.put("order", Consts.NEW_CARDS_DUE);
        dict.put("perDay", mReportLimit);
        return dict;
    }


    // Overridden: different delays for filtered cards.
    protected @NonNull JSONObject _lapseConf(@NonNull Card card) {
        DeckConfig conf = _cardConf(card);
        if (!card.isInDynamicDeck()) {
            return conf.getJSONObject("lapse");
        }
        // dynamic deck; override some attributes, use original deck for others
        DeckConfig oconf = getCol().getDecks().confForDid(card.getODid());
        JSONObject dict = new JSONObject();
        // original deck
        dict.put("minInt", oconf.getJSONObject("lapse").getInt("minInt"));
        dict.put("leechFails", oconf.getJSONObject("lapse").getInt("leechFails"));
        dict.put("leechAction", oconf.getJSONObject("lapse").getInt("leechAction"));
        dict.put("mult", oconf.getJSONObject("lapse").getDouble("mult"));
        dict.put("delays", oconf.getJSONObject("lapse").getJSONArray("delays"));
        // overrides
        dict.put("resched", conf.getBoolean("resched"));
        return dict;
    }


    protected @NonNull JSONObject _revConf(@NonNull Card card) {
        DeckConfig conf = _cardConf(card);
        if (!card.isInDynamicDeck()) {
            return conf.getJSONObject("rev");
        }
        return getCol().getDecks().confForDid(card.getODid()).getJSONObject("rev");
    }


    private boolean _previewingCard(@NonNull Card card) {
        DeckConfig conf = _cardConf(card);

        return conf.isDyn() && !conf.getBoolean("resched");
    }

    private int _previewDelay(@NonNull Card card) {
        return _cardConf(card).optInt("previewDelay", 10) * 60;
    }


    /**
     * Daily cutoff ************************************************************* **********************************
     * This function uses GregorianCalendar so as to be sensitive to leap years, daylight savings, etc.
     */

    /* Overridden: other way to count time*/
    public void _updateCutoff() {
        int oldToday = mToday == null ? 0 : mToday;

        SchedTimingTodayResponse timing = _timingToday();

        mToday = timing.getDaysElapsed();
        mDayCutoff = timing.getNextDayAt();

        if (oldToday != mToday) {
            getCol().log(mToday, mDayCutoff);
        }
        // update all daily counts, but don't save decks to prevent needless conflicts. we'll save on card answer
        // instead
        for (Deck deck : getCol().getDecks().all()) {
            update(deck);
        }
        // unbury if the day has rolled over
        int unburied = getCol().get_config("lastUnburied", 0);
        if (unburied < mToday) {
            SyncStatus.ignoreDatabaseModification(this::unburyCards);
            getCol().set_config("lastUnburied", mToday);
        }
    }

    protected void update(@NonNull Deck g) {
        for (String t : new String[] { "new", "rev", "lrn", "time" }) {
            String key = t + "Today";
            JSONArray tToday = g.getJSONArray(key);
            if (g.getJSONArray(key).getInt(0) != mToday) {
                tToday.put(0, mToday);
                tToday.put(1, 0);
            }
        }
    }


    public void _checkDay() {
        // check if the day has rolled over
        if (getTime().intTime() > mDayCutoff) {
            reset();
        }
    }

    /** true if there are cards in learning, with review due the same
     * day, in the selected decks. */
    /* not in upstream anki. As revDue and newDue, it's used to check
     * what to do when a deck is selected in deck picker. When this
     * method is called, we already know that no cards is due
     * immediately. It answers whether cards will be due later in the
     * same deck. */
    public boolean hasCardsTodayAfterStudyAheadLimit() {
        if (!BackendFactory.getDefaultLegacySchema()) {
            return super.hasCardsTodayAfterStudyAheadLimit();
        }

        return getCol().getDb().queryScalar(
                "SELECT 1 FROM cards WHERE did IN " + _deckLimit()
                + " AND queue = " + Consts.QUEUE_TYPE_LRN + " LIMIT 1") != 0;
    }


    public boolean haveBuriedSiblings() {
        return haveBuriedSiblings(getCol().getDecks().active());
    }


    private boolean haveBuriedSiblings(@NonNull List<Long> allDecks) {
        // Refactored to allow querying an arbitrary deck
        String sdids = Utils.ids2str(allDecks);
        int cnt = getCol().getDb().queryScalar(
                "select 1 from cards where queue = " + Consts.QUEUE_TYPE_SIBLING_BURIED + " and did in " + sdids + " limit 1");
        return cnt != 0;
    }


    public boolean haveManuallyBuried() {
        return haveManuallyBuried(getCol().getDecks().active());
    }


    private boolean haveManuallyBuried(@NonNull List<Long> allDecks) {
        // Refactored to allow querying an arbitrary deck
        String sdids = Utils.ids2str(allDecks);
        int cnt = getCol().getDb().queryScalar(
                "select 1 from cards where queue = " + Consts.QUEUE_TYPE_MANUALLY_BURIED + " and did in " + sdids + " limit 1");
        return cnt != 0;
    }


    public boolean haveBuried() {
        if (!BackendFactory.getDefaultLegacySchema()) {
            return super.haveBuried();
        }

        return haveManuallyBuried() || haveBuriedSiblings();
    }


    /*
      Next time reports ********************************************************
      ***************************************
     */

    /**
     * Return the next interval for CARD, in seconds.
     */
    // Overridden
    public long nextIvl(@NonNull Card card, @Consts.BUTTON_TYPE int ease) {
        // preview mode?
        if (_previewingCard(card)) {
            if (ease == Consts.BUTTON_ONE) {
                return _previewDelay(card);
            }
            return 0;
        }
        // (re)learning?
        if (card.getQueue() == Consts.QUEUE_TYPE_NEW || card.getQueue() == Consts.QUEUE_TYPE_LRN || card.getQueue() == Consts.QUEUE_TYPE_DAY_LEARN_RELEARN) {
            return _nextLrnIvl(card, ease);
        } else if (ease == Consts.BUTTON_ONE) {
            // lapse
            JSONObject conf = _lapseConf(card);
            if (conf.getJSONArray("delays").length() > 0) {
                return (long) (conf.getJSONArray("delays").getDouble(0) * 60.0);
            }
            return _lapseIvl(card, conf) * SECONDS_PER_DAY;
        } else {
            // review
            boolean early = card.isInDynamicDeck() && (card.getODue() > mToday);
            if (early) {
                return _earlyReviewIvl(card, ease) * SECONDS_PER_DAY;
            } else {
                return _nextRevIvl(card, ease, false) * SECONDS_PER_DAY;
            }
        }
    }


    // this isn't easily extracted from the learn code
    // Overridden
    protected long _nextLrnIvl(@NonNull Card card, @Consts.BUTTON_TYPE int ease) {
        if (card.getQueue() == Consts.QUEUE_TYPE_NEW) {
            card.setLeft(_startingLeft(card));
        }
        JSONObject conf = _lrnConf(card);
        if (ease == Consts.BUTTON_ONE) {
            // fail
            return _delayForGrade(conf, conf.getJSONArray("delays").length());
        } else if (ease == Consts.BUTTON_TWO) {
            return _delayForRepeatingGrade(conf, card.getLeft());
        } else if (ease == Consts.BUTTON_FOUR) {
            return _graduatingIvl(card, conf, true, false) * SECONDS_PER_DAY;
        } else { // ease == 3
            int left = card.getLeft() % 1000 - 1;
            if (left <= 0) {
                // graduate
                return _graduatingIvl(card, conf, false, false) * SECONDS_PER_DAY;
            } else {
                return _delayForGrade(conf, left);
            }
        }
    }


    /*
      Suspending & burying ********************************************************** ********************************
     */

    /**
     * learning and relearning cards may be seconds-based or day-based;
     * other types map directly to queues
     *
     * Overridden: in V1, queue becomes type.
     */
    @NonNull
    protected String _restoreQueueSnippet() {
        return "queue = (case when type in (" + Consts.CARD_TYPE_LRN + "," + Consts.CARD_TYPE_RELEARNING + ") then\n" +
                "  (case when (case when odue then odue else due end) > 1000000000 then 1 else " + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + " end)\n" +
                "else\n" +
                "  type\n" +
                "end)  ";
    }

    /**
     * Overridden: in V1 only sibling buried exits.*/
    protected @NonNull String queueIsBuriedSnippet() {
        return " queue in (" + Consts.QUEUE_TYPE_SIBLING_BURIED + ", " + Consts.QUEUE_TYPE_MANUALLY_BURIED + ") ";
    }

    /**
     * Suspend cards.
     *
     * Overridden: in V1 remove from dyn and lrn
     */
    public void suspendCards(@NonNull long[] ids) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            super.suspendCards(ids);
            return;
        }

        getCol().log(ids);
        getCol().getDb().execute(
                "UPDATE cards SET queue = " + Consts.QUEUE_TYPE_SUSPENDED + ", mod = ?, usn = ? WHERE id IN "
                        + Utils.ids2str(ids),
                getTime().intTime(), getCol().usn());
    }


    /**
     * Unsuspend cards
     */
    public void unsuspendCards(@NonNull long[] ids) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            super.unsuspendCards(ids);
            return;
        }

        getCol().log(ids);
        getCol().getDb().execute(
                "UPDATE cards SET " + _restoreQueueSnippet() + ", mod = ?, usn = ?"
                        + " WHERE queue = " + Consts.QUEUE_TYPE_SUSPENDED + " AND id IN " + Utils.ids2str(ids),
                getTime().intTime(), getCol().usn());
    }

    @Override
    // Overridden: V1 also remove from dyns and lrn
    @VisibleForTesting
    public void buryCards(@NonNull long[] cids, boolean manual) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            super.buryCards(cids, manual);
            return;
        }

        int queue = manual ? Consts.QUEUE_TYPE_MANUALLY_BURIED : Consts.QUEUE_TYPE_SIBLING_BURIED;
        getCol().log(cids);
        getCol().getDb().execute("update cards set queue=?,mod=?,usn=? where id in " + Utils.ids2str(cids),
                queue, getTime().intTime(), getCol().usn());
    }

    @Override
    public void unburyCardsForDeck(long did, @NonNull UnburyType type) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            super.unburyCardsForDeck(did, type);
            return;
        }

        List<Long> dids = getCol().getDecks().childDids(did, getCol().getDecks().childMap());
        dids.add(did);
        unburyCardsForDeck(type, dids);
    }

    public void unburyCardsForDeck(@NonNull UnburyType type, @Nullable List<Long> allDecks) {
        String queue;
        switch (type) {
            case ALL :
                queue = queueIsBuriedSnippet();
                break;
            case MANUAL:
                queue = "queue = " + Consts.QUEUE_TYPE_MANUALLY_BURIED;
                break;
            case SIBLINGS:
                queue = "queue = " + Consts.QUEUE_TYPE_SIBLING_BURIED;
                break;
            default:
                throw new RuntimeException("unknown type");
        }

        String sids = Utils.ids2str(allDecks != null ? allDecks : getCol().getDecks().active());

        getCol().log(getCol().getDb().queryLongList("select id from cards where " + queue + " and did in " + sids));
        getCol().getDb().execute("update cards set mod=?,usn=?, " + _restoreQueueSnippet() + " where " + queue + " and did in " + sids,
                getTime().intTime(), getCol().usn());
    }


    /**
     * Bury all cards for note until next session.
     * @param nid The id of the targeted note.
     */
    public void buryNote(long nid) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            super.buryNote(nid);
            return;
        }

        long[] cids = Utils.collection2Array(getCol().getDb().queryLongList(
                "SELECT id FROM cards WHERE nid = ? AND queue >= " + Consts.CARD_TYPE_NEW, nid));
        buryCards(cids);
    }

    /**
     * Sibling spacing
     * ********************
     */

    protected void _burySiblings(@NonNull Card card) {
        ArrayList<Long> toBury = new ArrayList<>();
        JSONObject nconf = _newConf(card);
        boolean buryNew = nconf.optBoolean("bury", true);
        JSONObject rconf = _revConf(card);
        boolean buryRev = rconf.optBoolean("bury", true);
        // loop through and remove from queues
        try (Cursor cur = getCol().getDb().query(
                    "select id, queue from cards where nid=? and id!=? "+
                    "and (queue=" + Consts.QUEUE_TYPE_NEW + " or (queue=" + Consts.QUEUE_TYPE_REV + " and due<=?))", card.getNid(), card.getId(), mToday)) {
            while (cur.moveToNext()) {
                long cid = cur.getLong(0);
                int queue = cur.getInt(1);
                SimpleCardQueue queue_object;
                if (queue == Consts.QUEUE_TYPE_REV) {
                    queue_object = mRevQueue;
                    if (buryRev) {
                        toBury.add(cid);
                    }
                } else {
                    queue_object = mNewQueue;
                    if (buryNew) {
                        toBury.add(cid);
                    }
                }
                // even if burying disabled, we still discard to give
                // same-day spacing
                queue_object.remove(cid);
            }
        }
        // then bury
        if (!toBury.isEmpty()) {
            buryCards(Utils.collection2Array(toBury),false);
        }
    }


    /*
     * Resetting **************************************************************** *******************************
     */

    /** Put cards at the end of the new queue. */
    public void forgetCards(@NonNull List<Long> ids) {
        // Currently disabled, as this causes a breakage in some tests due to
        // the AnkiDroid implementation not using nextPos to determine next position.
        //        if (!BackendFactory.getDefaultLegacySchema()) {
        //            super.forgetCards(ids);
        //            return;
        //        }

        remFromDyn(ids);
        getCol().getDb().execute("update cards set type=" + Consts.CARD_TYPE_NEW + ",queue=" + Consts.QUEUE_TYPE_NEW + ",ivl=0,due=0,odue=0,factor="+Consts.STARTING_FACTOR +
                " where id in " + Utils.ids2str(ids));
        int pmax = getCol().getDb().queryScalar("SELECT max(due) FROM cards WHERE type=" + Consts.CARD_TYPE_NEW + "");
        // takes care of mod + usn
        sortCards(ids, pmax + 1);
        getCol().log(ids);
    }


    /**
     * Put cards in review queue with a new interval in days (min, max).
     *
     * @param ids The list of card ids to be affected
     * @param imin the minimum interval (inclusive)
     * @param imax The maximum interval (inclusive)
     */
    public void reschedCards(@NonNull List<Long> ids, int imin, int imax) {
        // Currently disabled, as this causes a breakage in the V2 tests due to
        // the use of a mocked time.
        //        if (!BackendFactory.getDefaultLegacySchema()) {
        //            super.reschedCards(ids, imin, imax);
        //            return;
        //        }

        ArrayList<Object[]> d = new ArrayList<>(ids.size());
        int t = mToday;
        long mod = getTime().intTime();
        Random rnd = new Random();
        for (long id : ids) {
            int r = rnd.nextInt(imax - imin + 1) + imin;
            d.add(new Object[] { Math.max(1, r), r + t, getCol().usn(), mod, RESCHEDULE_FACTOR, id });
        }
        remFromDyn(ids);
        getCol().getDb().executeMany(
                "update cards set type=" + Consts.CARD_TYPE_REV + ",queue=" + Consts.QUEUE_TYPE_REV + ",ivl=?,due=?,odue=0, " +
                        "usn=?,mod=?,factor=? where id=?", d);
        getCol().log(ids);
    }

    /**
     * Repositioning new cards **************************************************
     * *********************************************
     */

    public void sortCards(@NonNull List<Long> cids, int start, int step, boolean shuffle, boolean shift) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            super.sortCards(cids, start, step, shuffle, shift);
            return;
        }

        String scids = Utils.ids2str(cids);
        long now = getTime().intTime();
        ArrayList<Long> nids = new ArrayList<>(cids.size());
        // List of cid from `cids` and its `nid`
        ArrayList<Pair<Long, Long>> cid2nid = new ArrayList<>(cids.size());
        for (Long id : cids) {
            long nid = getCol().getDb().queryLongScalar("SELECT nid FROM cards WHERE id = ?", id);
            if (!nids.contains(nid)) {
                nids.add(nid);
            }
            cid2nid.add(new Pair<>(id, nid));
        }
        if (nids.isEmpty()) {
            // no new cards
            return;
        }
        // determine nid ordering
        HashMap<Long, Long> due = HashUtil.HashMapInit(nids.size());
        if (shuffle) {
            Collections.shuffle(nids);
        }
        for (int c = 0; c < nids.size(); c++) {
            due.put(nids.get(c), (long) (start + c * step));
        }
        int high = start + step * (nids.size() - 1);
        // shift?
        if (shift) {
            int low = getCol().getDb().queryScalar(
                    "SELECT min(due) FROM cards WHERE due >= ? AND type = " + Consts.CARD_TYPE_NEW + " AND id NOT IN " + scids,
                    start);
            if (low != 0) {
                int shiftBy = high - low + 1;
                getCol().getDb().execute(
                        "UPDATE cards SET mod = ?, usn = ?, due = due + ?"
                                + " WHERE id NOT IN " + scids + " AND due >= ? AND type = " + Consts.CARD_TYPE_NEW,
                        now, getCol().usn(), shiftBy, low);
            }
        }
        // reorder cards
        ArrayList<Object[]> d = new ArrayList<>(cids.size());
        for (Pair<Long, Long> pair : cid2nid) {
            Long cid = pair.first;
            Long nid = pair.second;
            d.add(new Object[] { due.get(nid), now, getCol().usn(), cid });
        }
        getCol().getDb().executeMany("UPDATE cards SET due = ?, mod = ?, usn = ? WHERE id = ?", d);
    }


    public void randomizeCards(long did) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            super.randomizeCards(did);
            return;
        }

        List<Long> cids = getCol().getDb().queryLongList("select id from cards where type = " + Consts.CARD_TYPE_NEW + " and did = ?", did);
        sortCards(cids, 1, 1, true, false);
    }


    public void orderCards(long did) {
        if (!BackendFactory.getDefaultLegacySchema()) {
            super.orderCards(did);
            return;
        }

        List<Long> cids = getCol().getDb().queryLongList("SELECT id FROM cards WHERE type = " + Consts.CARD_TYPE_NEW + " AND did = ? ORDER BY nid", did);
        sortCards(cids, 1, 1, false, false);
    }


    /**
     * Changing scheduler versions **************************************************
     * *********************************************
     */

    private void _emptyAllFiltered() {
        getCol().getDb().execute("update cards set did = odid, queue = (case when type = " + Consts.CARD_TYPE_LRN + " then " + Consts.QUEUE_TYPE_NEW + " when type = " + Consts.CARD_TYPE_RELEARNING + " then " + Consts.QUEUE_TYPE_REV + " else type end), type = (case when type = " + Consts.CARD_TYPE_LRN + " then " + Consts.CARD_TYPE_NEW + " when type = " + Consts.CARD_TYPE_RELEARNING + " then " + Consts.CARD_TYPE_REV + " else type end), due = odue, odue = 0, odid = 0, usn = ? where odid != 0",
                             getCol().usn());
    }


    private void _removeAllFromLearning() {
        _removeAllFromLearning(2);
    }

    private void _removeAllFromLearning(int schedVer) {
        // remove review cards from relearning
        if (schedVer == 1) {
            getCol().getDb().execute("update cards set due = odue, queue = " + Consts.QUEUE_TYPE_REV + ", type = " + Consts.CARD_TYPE_REV + ", mod = ?, usn = ?, odue = 0 where queue in (" + Consts.QUEUE_TYPE_LRN + "," + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + ") and type in (" + Consts.CARD_TYPE_REV + "," + Consts.CARD_TYPE_RELEARNING + ")",
                                 getTime().intTime(), getCol().usn());
        } else {
            getCol().getDb().execute("update cards set due = ?+ivl, queue = " + Consts.QUEUE_TYPE_REV + ", type = " + Consts.CARD_TYPE_REV + ", mod = ?, usn = ?, odue = 0 where queue in (" + Consts.QUEUE_TYPE_LRN + "," + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + ") and type in (" + Consts.CARD_TYPE_REV + "," + Consts.CARD_TYPE_RELEARNING + ")",
                                 mToday, getTime().intTime(), getCol().usn());
        }


        // remove new cards from learning
        forgetCards(getCol().getDb().queryLongList("select id from cards where queue in (" + Consts.QUEUE_TYPE_LRN + "," + Consts.QUEUE_TYPE_DAY_LEARN_RELEARN + ")"));
    }


    // v1 doesn't support buried/suspended (re)learning cards
    private void _resetSuspendedLearning() {
        getCol().getDb().execute("update cards set type = (case when type = " + Consts.CARD_TYPE_LRN + " then " + Consts.CARD_TYPE_NEW + " when type in (" + Consts.CARD_TYPE_REV + ", " + Consts.CARD_TYPE_RELEARNING + ") then " + Consts.CARD_TYPE_REV + " else type end), due = (case when odue then odue else due end), odue = 0, mod = ?, usn = ? where queue < 0",
                             getTime().intTime(), getCol().usn());
    }


    // no 'manually buried' queue in v1
    private void _moveManuallyBuried() {
        getCol().getDb().execute("update cards set queue=" + Consts.QUEUE_TYPE_SIBLING_BURIED + ", mod=? where queue=" + Consts.QUEUE_TYPE_MANUALLY_BURIED,
                             getTime().intTime());
    }

    // adding 'hard' in v2 scheduler means old ease entries need shifting
    // up or down
    private void _remapLearningAnswers(@NonNull String sql) {
        getCol().getDb().execute("update revlog set " + sql + " and type in (" + Consts.REVLOG_LRN + ", " + Consts.REVLOG_RELRN + ")");
    }

    public void moveToV1() {
        _emptyAllFiltered();
        _removeAllFromLearning();

        _moveManuallyBuried();
        _resetSuspendedLearning();
        _remapLearningAnswers("ease=ease-1 where ease in (" + Consts.BUTTON_THREE + "," + Consts.BUTTON_FOUR + ")");
    }


    public void moveToV2() {
        _emptyAllFiltered();
        _removeAllFromLearning(1);
        _remapLearningAnswers("ease=ease+1 where ease in (" + Consts.BUTTON_TWO + "," + Consts.BUTTON_THREE + ")");
    }


    /*
     * ***********************************************************
     * The methods below are not in LibAnki.
     * ***********************************************************
     */
    // Overridden: In sched v1, a single type of burying exist
    public boolean haveBuried(long did) {
        List<Long> all = new ArrayList<>(getCol().getDecks().children(did).values());
        all.add(did);
        return haveBuriedSiblings(all) || haveManuallyBuried(all);
    }

    public void unburyCardsForDeck(long did) {
        List<Long> all = new ArrayList<>(getCol().getDecks().children(did).values());
        all.add(did);
        unburyCardsForDeck(ALL, all);
    }


    @NonNull
    public String getName() {
        return "std2";
    }


    public int getToday() {
        return mToday;
    }


    public void setToday(int today) {
        mToday = today;
    }


    public long getDayCutoff() {
        return mDayCutoff;
    }


    public int getReps(){
        return mReps;
    }

    protected void incrReps() {
        mReps++;
    }


    protected void decrReps() {
        mReps--;
    }

    /**
     * Change the counts to reflect that `card` should not be counted anymore. In practice, it means that the card has
     * been sent to the reviewer. Either through `getCard()` or through `undo`. Assumes that card's queue has not yet
     * changed.
     * Overridden*/
    public void decrementCounts(@Nullable Card discardCard) {
        if (discardCard == null) {
            return;
        }
        switch (discardCard.getQueue()) {
        case Consts.QUEUE_TYPE_NEW:
            mNewCount--;
            break;
        case Consts.QUEUE_TYPE_LRN:
        case Consts.QUEUE_TYPE_DAY_LEARN_RELEARN:
        case Consts.QUEUE_TYPE_PREVIEW:
            mLrnCount --;
            // In the case of QUEUE_TYPE_LRN, it is -= discardCard.getLeft() / 1000; in sched v1
            break;
        case Consts.QUEUE_TYPE_REV:
            mRevCount--;
            break;
        }
    }


    /**
     * Sorts a card into the lrn queue LIBANKI: not in libanki
     */
    protected void _sortIntoLrn(long due, long id) {
        if (!mLrnQueue.isFilled()) {
            // We don't want to add an element to the queue if it's not yet assumed to have its normal content.
            // Adding anything is useless while the queue awaits being filled
            return;
        }
        ListIterator<LrnCard> i = mLrnQueue.listIterator();
        while (i.hasNext()) {
            if (i.next().getDue() > due) {
                i.previous();
                break;
            }
        }
        i.add(new LrnCard(getCol(), due, id));
    }


    public boolean leechActionSuspend(@NonNull Card card) {
        JSONObject conf = _cardConf(card).getJSONObject("lapse");
        return conf.getInt("leechAction") == Consts.LEECH_SUSPEND;
    }


    public void setContext(@Nullable WeakReference<Activity> contextReference) {
        mContextReference = contextReference;
    }

    @Override
    public void undoReview(@NonNull Card oldCardData, boolean wasLeech) {
        // remove leech tag if it didn't have it before
        if (!wasLeech && oldCardData.note().hasTag("leech")) {
            oldCardData.note().delTag("leech");
            oldCardData.note().flush();
        }
        Timber.i("Undo Review of card %d, leech: %b", oldCardData.getId(), wasLeech);
        // write old data
        oldCardData.flush(false);
        DeckConfig conf = _cardConf(oldCardData);
        boolean previewing = conf.isDyn() && ! conf.getBoolean("resched");
        if (! previewing) {
            // and delete revlog entry
            long last = getCol().getDb().queryLongScalar("SELECT id FROM revlog WHERE cid = ? ORDER BY id DESC LIMIT 1", oldCardData.getId());
            getCol().getDb().execute("DELETE FROM revlog WHERE id = " + last);
        }
        // restore any siblings
        getCol().getDb().execute("update cards set queue=type,mod=?,usn=? where queue=" + Consts.QUEUE_TYPE_SIBLING_BURIED + " and nid=?", getTime().intTime(), getCol().usn(), oldCardData.getNid());
        // and finally, update daily count
        @Consts.CARD_QUEUE int n = (oldCardData.getQueue() == Consts.QUEUE_TYPE_DAY_LEARN_RELEARN || oldCardData.getQueue() == Consts.QUEUE_TYPE_PREVIEW) ? Consts.QUEUE_TYPE_LRN : oldCardData.getQueue();
        String type = (new String[]{"new", "lrn", "rev"})[n];
        _updateStats(oldCardData, type, -1);
        decrReps();
    }


    @NonNull
    public Time getTime() {
        return TimeManager.INSTANCE.getTime();
    }


    /** Notifies the scheduler that there is no more current card. This is the case when a card is answered, when the
     * scheduler is reset... #5666 */
    public void discardCurrentCard() {
        mCurrentCard = null;
        mCurrentCardParentsDid = null;
    }

    /**
     * This imitate the action of the method answerCard, except that it does not change the state of any card.
     *
     * It means in particular that: + it removes the siblings of card from all queues + change the next card if required
     * it also set variables, so that when querying the next card, the current card can be taken into account.
     */
    public void setCurrentCard(@NonNull Card card) {
        mCurrentCard = card;
        long did = card.getDid();
        List<Deck> parents = getCol().getDecks().parents(did);
        List<Long> currentCardParentsDid = new ArrayList<>(parents.size() + 1);
        for (JSONObject parent : parents) {
            currentCardParentsDid.add(parent.getLong("id"));
        }
        currentCardParentsDid.add(did);
        // We set the member only once it is filled, to ensure we avoid null pointer exception if `discardCurrentCard`
        // were called during `setCurrentCard`.
        mCurrentCardParentsDid = currentCardParentsDid;
        _burySiblings(card);
        // if current card is next card or in the queue
        mRevQueue.remove(card.getId());
        mNewQueue.remove(card.getId());
    }

    protected boolean currentCardIsInQueueWithDeck(@Consts.CARD_QUEUE int queue, long did) {
        // mCurrentCard may be set to null when the reviewer gets closed. So we copy it to be sure to avoid NullPointerException
        Card currentCard = mCurrentCard;
        List<Long> currentCardParentsDid = mCurrentCardParentsDid;
        return currentCard != null && currentCard.getQueue() == queue && currentCardParentsDid != null && currentCardParentsDid.contains(did);
    }
    
    @Override
    @VisibleForTesting
    public @Consts.BUTTON_TYPE int getGoodNewButton() {
        return Consts.BUTTON_THREE;
    }
}