package fr.ybonnel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import twitter4j.*;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class TestOldTweets {

    private static Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").create();

    private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");

    private static AtomicInteger counter = new AtomicInteger(0);


    private static Twitter myTwitter = TwitterFactory.getSingleton();

    private static PreparedStatement preparedStatement;
    private static Connection connection;

    public static void main(String[] args) throws TwitterException, ClassNotFoundException, SQLException, InterruptedException {
        Class.forName("org.postgresql.Driver");

        connection = DriverManager.getConnection("jdbc:postgresql://localhost:5434/devoxx", "devoxx", "devoxx");

        preparedStatement = connection.prepareStatement("INSERT INTO tweets_devoxx_big (id, handle, tweet) VALUES (?, ?, ?::JSONB)");

        AtomicLong cursor = new AtomicLong(-1);
        List<Long> idUsers = new ArrayList<>();
        do {
            IDs ids = callTwitter(twitter -> twitter.getFollowersIDs("devoxxfr", cursor.get()));
            for (long id : ids.getIDs()) {
                idUsers.add(id);
            }
            cursor.set(ids.getNextCursor());
        } while (cursor.get() != 0);

        List<User> users = new ArrayList<>();
        while (!idUsers.isEmpty()) {
            System.out.println("Lookup users " + users.size() + "/" + (users.size() + idUsers.size()));
            long[] idsToLookup = idUsers.subList(0, Math.min(100, idUsers.size())).stream().mapToLong(id -> id).toArray();
            idUsers = idUsers.size() <= 100 ? new ArrayList<>() : idUsers.subList(100, idUsers.size());
            users.addAll(callTwitter(twitter -> twitter.lookupUsers(idsToLookup)));
        }

        users.add(callTwitter(twitter -> twitter.showUser("devoxxfr")));

        Collections.reverse(users);

        nb = users.size();
        for (User user : users) {
            processTweetsOfUser(user);
        }

        connection.close();

    }

    interface CallTwitter<T> {
        T call(Twitter twitter) throws TwitterException;
    }

    private static <T> T callTwitter(CallTwitter<T> call) throws InterruptedException {
        for (; ; ) {
            try {
                return call.call(myTwitter);
            } catch (TwitterException twitterException) {
                if (twitterException.getRateLimitStatus() != null) {
                    int secondsToWait = twitterException.getRateLimitStatus().getSecondsUntilReset() + 1;
                    System.err.println("Rate Limit, wait " + secondsToWait + "s before next call");
                    Thread.sleep(TimeUnit.SECONDS.toMillis(Math.abs(secondsToWait)));
                } else {
                    twitterException.printStackTrace();
                    System.err.println("Wait 30s before next call");
                    for (int index = 1; index <= 30; index++) {
                        System.err.print(".");
                        Thread.sleep(TimeUnit.SECONDS.toMillis(1));
                    }
                    System.err.println();
                }
            }
        }
    }

    private static int count = 0;
    private static int nb = 0;

    private static void processTweetsOfUser(User user) throws TwitterException, InterruptedException, SQLException {
        AtomicInteger page = new AtomicInteger(1);
        count++;
        System.out.println("Tweets of " + user.getName() + " / " + user.getScreenName() + " - " + count + "/" + nb);

        if (user.isProtected()) {
            System.out.println("Protected user");
            return;
        }
        if (user.getStatusesCount() <= 0 || user.getStatus() == null) {
            System.out.println("No tweets");
            return;
        }

        ResponseList<Status> tweets;

        if (tweetExistInDatabase(user.getStatus().getId())) {
            System.out.println("@" + user.getScreenName() + "(" + user.getName() + ") already totally scrapped");
            return;
        }

        do {
            tweets = callTwitter(twitter -> twitter.getUserTimeline(user.getId(), new Paging(page.get(), 200)));
            if (tweets.isEmpty()) {
                return;
            }

            boolean lastTweetExistInDatabase = tweetExistInDatabase(tweets.get(tweets.size() - 1).getId());
            tweets.forEach(TestOldTweets::processTweet);

            if (lastTweetExistInDatabase) {
                return;
            }

            page.incrementAndGet();
        } while (!tweets.isEmpty());
    }


    private static void processTweet(Status tweet) {
        String json = gson.toJson(tweet);
        long id = tweet.getId();
        String handle = tweet.getUser().getScreenName();

        try {
            if (!tweetExistInDatabase(id)) {
                preparedStatement.setLong(1, id);
                preparedStatement.setString(2, handle);
                preparedStatement.setString(3, json.replaceAll("\\\\u0000", " "));
                preparedStatement.executeUpdate();
                System.out.println(counter.incrementAndGet() + " - " + sdf.format(tweet.getCreatedAt()) + " : " + count + "/" + nb + "@" + tweet.getUser().getScreenName() + "(" + tweet.getUser().getName() + "):" + tweet.getText());
            } else {
                System.out.println(counter.incrementAndGet() + " - Already scrapped");
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private static boolean tweetExistInDatabase(long id) throws SQLException {
        return connection.prepareCall("SELECT 1 as exist FROM tweets_devoxx_big WHERE id = " + id).executeQuery().next();
    }
}
